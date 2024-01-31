// Copyright 2022-2023 The Connect Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.connectrpc.okhttp

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.StreamResult
import com.connectrpc.http.Cancelable
import com.connectrpc.http.HTTPClientInterface
import com.connectrpc.http.HTTPRequest
import com.connectrpc.http.HTTPResponse
import com.connectrpc.http.Stream
import com.connectrpc.http.TracingInfo
import com.connectrpc.http.UnaryHTTPRequest
import com.connectrpc.protocols.CONNECT_PROTOCOL_VERSION_KEY
import com.connectrpc.protocols.CONNECT_PROTOCOL_VERSION_VALUE
import com.connectrpc.protocols.GETConstants
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.internal.http.HttpMethod
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * The OkHttp implementation of HTTPClientInterface.
 */
class ConnectOkHttpClient @JvmOverloads constructor(
    unaryClient: OkHttpClient = OkHttpClient(),
    streamClient: OkHttpClient = unaryClient,
) : HTTPClientInterface {
    private val unaryClient = applyNetworkInterceptor(unaryClient)
    private val streamClient = applyNetworkInterceptor(streamClient)

    private fun applyNetworkInterceptor(client: OkHttpClient): OkHttpClient {
        return client.newBuilder()
            .addNetworkInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val resp = chain.proceed(chain.request())
                    // The Connect protocol spec currently suggests 408 as the HTTP status code for
                    // cancelled and deadline exceeded errors with unary methods.. However, the
                    // spec for this status code states that the request may be retried without
                    // modification (even non-idempotent requests), and okhttp does in fact retry
                    // such errors. (So do many browsers.) So if we see that error code AND an
                    // application/json body (which suggests this is such a Connect error, vs. a
                    // real client timeout error or a 408 generated by a middlebox), we will
                    // transform the error in a network interceptor to prevent okhttp from
                    // retrying it.
                    if (resp.code == 408 && isConnectUnary(chain.request())) {
                        val mediaType = resp.headers["Content-Type"]?.toMediaTypeOrNull()
                        if (mediaType != null && mediaType.type == "application" && mediaType.subtype == "json") {
                            return resp
                                .newBuilder()
                                .code(499)
                                .message(resp.message + REVISED_CODE_SUFFIX)
                                .build()
                        }
                    }
                    return resp
                }
            })
            .build()
    }

    private fun isConnectUnary(req: Request): Boolean {
        return when (req.method) {
            "POST" -> req.headers[CONNECT_PROTOCOL_VERSION_KEY].orEmpty() == CONNECT_PROTOCOL_VERSION_VALUE &&
                req.headers["Content-Type"].orEmpty().startsWith("application/")
            "GET" -> req.url.queryParameter(GETConstants.CONNECT_VERSION_QUERY_PARAM_KEY) == GETConstants.CONNECT_VERSION_QUERY_PARAM_VALUE
            else -> false
        }
    }

    override fun unary(request: UnaryHTTPRequest, onResult: (HTTPResponse) -> Unit): Cancelable {
        val builder = Request.Builder()
        for (entry in request.headers) {
            for (values in entry.value) {
                builder.addHeader(entry.key, values)
            }
        }
        val content = request.message
        val method = request.httpMethod.string
        val requestBody = if (HttpMethod.requiresRequestBody(method)) {
            object : RequestBody() {
                override fun contentType() = request.contentType.toMediaType()
                override fun contentLength() = content.size
                override fun writeTo(sink: BufferedSink) {
                    // We make a copy so that this body is not "one shot",
                    // meaning that the okhttp library may automatically
                    // retry the request under certain conditions. If we
                    // didn't copy it, then reading it here would consume
                    // it and then a retry would only see an empty body.
                    content.copy().readAll(sink)
                }
            }
        } else {
            null
        }
        val callRequest = builder
            .url(request.url)
            .method(method, requestBody)
            .build()
        val newCall = unaryClient.newCall(callRequest)
        val cancelable = {
            newCall.cancel()
        }
        try {
            newCall.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val code = codeFromException(newCall.isCanceled(), e)
                        onResult(
                            HTTPResponse(
                                code = code,
                                headers = emptyMap(),
                                message = Buffer(),
                                trailers = emptyMap(),
                                cause = ConnectException(
                                    code,
                                    message = e.message,
                                    exception = e,
                                ),
                                tracingInfo = null,
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Unary requests will need to read the entire body to access trailers.
                        val responseBuffer = response.body?.source()?.use { bufferedSource ->
                            val buffer = Buffer()
                            buffer.writeAll(bufferedSource)
                            buffer
                        }
                        val originalStatus = response.originalCode()
                        onResult(
                            HTTPResponse(
                                code = Code.fromHTTPStatus(originalStatus),
                                headers = response.headers.toLowerCaseKeysMultiMap(),
                                message = responseBuffer ?: Buffer(),
                                trailers = response.trailers().toLowerCaseKeysMultiMap(),
                                tracingInfo = TracingInfo(httpStatus = originalStatus),
                            ),
                        )
                    }
                },
            )
        } catch (e: Throwable) {
            onResult(
                HTTPResponse(
                    code = Code.UNKNOWN,
                    headers = emptyMap(),
                    message = Buffer(),
                    trailers = emptyMap(),
                    cause = ConnectException(
                        Code.UNKNOWN,
                        message = e.message,
                        exception = e,
                    ),
                    tracingInfo = null,
                ),
            )
        }
        return cancelable
    }

    override fun stream(
        request: HTTPRequest,
        duplex: Boolean,
        onResult: suspend (StreamResult<Buffer>) -> Unit,
    ): Stream {
        return streamClient.initializeStream(request, duplex, onResult)
    }
}

internal fun Headers.toLowerCaseKeysMultiMap(): Map<String, List<String>> {
    return this.asSequence().groupBy(
        { it.first.lowercase() },
        { it.second },
    )
}

internal fun codeFromException(callCanceled: Boolean, e: Exception): Code {
    return if ((e is InterruptedIOException && e.message == "timeout") ||
        e is SocketTimeoutException
    ) {
        Code.DEADLINE_EXCEEDED
    } else if (e is IOException && callCanceled) {
        Code.CANCELED
    } else {
        Code.UNKNOWN
    }
}

internal const val REVISED_CODE_SUFFIX = " |originally 408|"

fun Response.originalCode(): Int {
    // 499 code could have been translated from 408 on the wire
    // (via network interceptor, to avoid okhttp's auto-retry on
    // 408 status codes). If so, return the original 408.
    return if (code == 499 && message.endsWith(REVISED_CODE_SUFFIX)) {
        408
    } else {
        code
    }
}
