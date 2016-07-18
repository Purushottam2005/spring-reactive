/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.converter.reactive;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.core.ResolvableType;
import org.springframework.core.codec.CodecException;
import org.springframework.core.codec.Encoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpInputMessage;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.util.Assert;
import org.springframework.web.reactive.sse.SseEvent;

/**
 * @author Arjen Poutsma
 */
public class SseEventHttpMessageConverter implements HttpMessageConverter<Object> {

	private static final MediaType TEXT_EVENT_STREAM =
			new MediaType("text", "event-stream");

	private final List<Encoder<?>> dataEncoders;

	public SseEventHttpMessageConverter(List<Encoder<?>> dataEncoders) {
		Assert.notNull(dataEncoders, "'dataEncoders' must not be null");
		this.dataEncoders = dataEncoders;
	}

	@Override
	public boolean canWrite(ResolvableType type, MediaType mediaType) {
		return mediaType == null || TEXT_EVENT_STREAM.isCompatibleWith(mediaType);
	}

	@Override
	public List<MediaType> getWritableMediaTypes() {
		return Collections.singletonList(TEXT_EVENT_STREAM);
	}

	@Override
	public Mono<Void> write(Publisher<?> inputStream, ResolvableType type,
			MediaType contentType, ReactiveHttpOutputMessage outputMessage) {

		outputMessage.getHeaders().setContentType(TEXT_EVENT_STREAM);

		DataBufferFactory bufferFactory = outputMessage.bufferFactory();
		Flux<Publisher<DataBuffer>> body = encode(inputStream, bufferFactory, type).
				concatWith(Flux.never());

		return outputMessage.writeAndFlushWith(body);
	}

	private Flux<Publisher<DataBuffer>> encode(Publisher<?> inputStream,
			DataBufferFactory bufferFactory, ResolvableType type) {

		return Flux.from(inputStream).map(input -> {
			SseEvent event =
					(SseEvent.class.equals(type.getRawClass()) ? (SseEvent) input :
							new SseEvent(input));

			StringBuilder sb = new StringBuilder();

			if (event.getId() != null) {
				sb.append("id:");
				sb.append(event.getId());
				sb.append("\n");
			}

			if (event.getName() != null) {
				sb.append("event:");
				sb.append(event.getName());
				sb.append("\n");
			}

			if (event.getReconnectTime() != null) {
				sb.append("retry:");
				sb.append(event.getReconnectTime().toString());
				sb.append("\n");
			}

			if (event.getComment() != null) {
				sb.append(":");
				sb.append(event.getComment().replaceAll("\\n", "\n:"));
				sb.append("\n");
			}

			Object data = event.getData();
			Flux<DataBuffer> dataBuffer = Flux.empty();
			MediaType mediaType =
					(event.getMediaType() == null ? MediaType.ALL : event.getMediaType());
			if (data != null) {
				sb.append("data:");
				if (data instanceof String) {
					sb.append(((String) data).replaceAll("\\n", "\ndata:")).append("\n");
				}
				else {
					Optional<Encoder<?>> encoder = dataEncoders.stream().filter(e -> e
							.canEncode(ResolvableType.forClass(data.getClass()),
									mediaType)).findFirst();

					if (encoder.isPresent()) {
						dataBuffer = ((Encoder<Object>) encoder.get())
								.encode(Mono.just(data), bufferFactory,
										ResolvableType.forClass(data.getClass()),
										mediaType)
								.concatWith(encodeString("\n", bufferFactory));
					}
					else {
						throw new CodecException("No suitable encoder found!");
					}
				}
			}

			// Keep the SSE connection open even for cold stream in order to avoid
			// unexpected browser reconnection
			return Flux.concat(encodeString(sb.toString(), bufferFactory), dataBuffer,
					encodeString("\n", bufferFactory));
		});

	}

	private Mono<DataBuffer> encodeString(String str, DataBufferFactory bufferFactory) {
		byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
		DataBuffer buffer = bufferFactory.allocateBuffer(bytes.length).write(bytes);
		return Mono.just(buffer);
	}

	// Unsupported reading methods

	@Override
	public boolean canRead(ResolvableType type, MediaType mediaType) {
		return false;
	}

	@Override
	public List<MediaType> getReadableMediaTypes() {
		return Collections.emptyList();
	}

	@Override
	public Flux<Object> read(ResolvableType type, ReactiveHttpInputMessage inputMessage) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Mono<Object> readMono(ResolvableType type,
			ReactiveHttpInputMessage inputMessage) {
		throw new UnsupportedOperationException();
	}
}
