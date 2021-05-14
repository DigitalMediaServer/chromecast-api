/*
 * Copyright 2014 Vitaly Litvak (vitavaque@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.digitalmediaserver.chromecast.api;

import static org.digitalmediaserver.chromecast.api.Util.readB32Int;
import static org.digitalmediaserver.chromecast.api.Util.writeB32Int;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.digitalmediaserver.chromecast.api.CastChannel.CastMessage;
import org.digitalmediaserver.chromecast.api.CastChannel.CastMessage.PayloadType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Internal class for low-level communication with ChromeCast device. Should
 * never be used directly, use {@link ChromeCast} methods instead
 */
public class Channel implements Closeable {

	private static final Logger LOGGER = LoggerFactory.getLogger(Channel.class);
	public static final Marker CHROMECAST_API_MARKER = MarkerFactory.getMarker("chromecast-api"); //TODO: (Nad) Use Marker everywhere..

	/**
	 * Period for sending ping requests (in ms)
	 */
	protected static final long PING_PERIOD = 10 * 1000; //TODO: (Nad) 5 sec suggested in doc, was 30

	/**
	 * Default value of much time to wait until request is processed
	 */
	protected static final long DEFAULT_REQUEST_TIMEOUT = 30 * 1000;

	protected static final String DEFAULT_RECEIVER_ID = "receiver-0";

	protected static final JsonSubTypes.Type[] STANDARD_RESPONSE_TYPES =
		StandardResponse.class.getAnnotation(JsonSubTypes.class).value();

	@Nonnull
	protected static final Executor EXECUTOR = createExecutor();

	protected final EventListenerHolder eventListener;

	private static void warn(String message, Exception ex) { //TODO: (Nad) Remove
		LOGGER.warn(CHROMECAST_API_MARKER, "{}, caused by {}", message, ex.toString());
	}

	@Nonnull
	protected final Object socketLock = new Object();

	/**
	 * Single socket instance for transfers
	 */
	@GuardedBy("socketLock")
	protected Socket socket; //TODO: (Nad) Must be sync'ed

	/**
	 * Address of ChromeCast
	 */
	@Nonnull
	protected final InetSocketAddress address;

	/** The name used for the remote party in logging */
	@Nonnull
	protected final String remoteName;

	/**
	 * The sender id used in this channel
	 */
	@Nonnull
	protected final String senderId;

	/**
	 * Timer for PING requests
	 */
	@GuardedBy("socketLock")
	protected Timer pingTimer;

	/**
	 * The {@link Thread} that delegates incoming requests to processing threads.
	 */
	@GuardedBy("socketLock")
	protected InputHandler inputHandler;

	/**
	 * Counter for producing request numbers
	 */
	private final AtomicLong requestCounter = new AtomicLong(new Random().nextInt(65536) + 1L);

	/**
	 * Processors of requests by their identifiers
	 */
	private final Map<Long, ResultProcessor<? extends Response>> requests = new ConcurrentHashMap<>(); //TODO: (Nad) This

	/**
	 * Single mapper object for marshalling JSON
	 */
	@Nonnull
	private final ObjectMapper jsonMapper = JacksonHelper.createJSONMapper();

	/**
	 * Destination ids of sessions opened within this channel
	 */
	private Set<String> sessions = new HashSet<>();

//	/**
//	 * Indicates that this channel was closed (explicitly, by remote host or for
//	 * some connectivity issue)
//	 */
//	private volatile boolean closed = true;
//	private final Object closedSync = new Object(); //TODO: (Nad) Problemo

	/**
	 * How much time to wait until request is processed
	 */
	private volatile long requestTimeout = DEFAULT_REQUEST_TIMEOUT;

	public Channel(
		@Nonnull String host,
		@Nonnull String remoteName,
		@Nullable String senderId,
		EventListenerHolder eventListener
	) {
		this(host, 8009, remoteName, senderId, eventListener);
	}

	public Channel(
		@Nonnull String host,
		int port,
		@Nonnull String remoteName,
		@Nullable String senderId,
		EventListenerHolder eventListener
	) {
		if (Util.isBlank(host)) {
			throw new IllegalArgumentException("host cannot be blank");
		}
		if (Util.isBlank(remoteName)) {
			throw new IllegalArgumentException("remoteName cannot be blank");
		}
		if (Util.isBlank(senderId)) {
			throw new IllegalArgumentException("senderId cannot be blank");
		}
		this.address = new InetSocketAddress(host, port);
		this.remoteName = remoteName;
		this.senderId = senderId;
		this.eventListener = eventListener;
	}

	//TODO: (Nad) WIP
	public boolean connect() throws IOException, NoSuchAlgorithmException, KeyManagementException {
		synchronized (socketLock) {
			if (socket != null && socket.isConnected() && !socket.isClosed()) {
				// Already connected, nothing to do
				return false;
			}

			if (socket == null || socket.isClosed()) {
				SSLContext sc = SSLContext.getInstance("SSL");
				sc.init(null, new TrustManager[] {new X509TrustAllManager()}, new SecureRandom());
				socket = sc.getSocketFactory().createSocket();
				socket.setSoTimeout(0);
				socket.connect(address);
			}

			// Authenticate
			CastChannel.DeviceAuthMessage authMessage = CastChannel.DeviceAuthMessage.newBuilder()
				.setChallenge(CastChannel.AuthChallenge.newBuilder().build())
				.build();

			CastMessage msg = CastMessage.newBuilder()
				.setDestinationId(DEFAULT_RECEIVER_ID)
				.setNamespace("urn:x-cast:com.google.cast.tp.deviceauth")
				.setPayloadType(CastMessage.PayloadType.BINARY)
				.setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
				.setSourceId(senderId)
				.setPayloadBinary(authMessage.toByteString())
				.build();

			write(msg);
			CastMessage response = readMessage(socket.getInputStream());
			CastChannel.DeviceAuthMessage authResponse = CastChannel.DeviceAuthMessage.parseFrom(response.getPayloadBinary());
			if (authResponse.hasError()) {
				throw new ChromeCastException("Authentication failed: " + authResponse.getError().getErrorType().toString());
			}

			// Start input handler
			inputHandler = new InputHandler(socket.getInputStream());
			inputHandler.start();

			// Send 'CONNECT' message to start session
			write("urn:x-cast:com.google.cast.tp.connection", StandardMessage.connect(), DEFAULT_RECEIVER_ID);

			// Start regular pinging
			PingTask pingTask = new PingTask();
			pingTimer = new Timer(remoteName + " PING timer");
			pingTimer.schedule(pingTask, 1000, PING_PERIOD);
		}

		//TODO: (Nad) Connect event here, use executor..?
		notifyListenerOfConnectionEvent(true);

		return true;
	}

	@Override
	public void close() throws IOException {
		synchronized (socketLock) {
			if (socket == null || socket.isClosed() || !socket.isConnected()) {
				// Already closed
				return;
			}

			if (pingTimer != null) {
				pingTimer.cancel();
				pingTimer = null;
			}

			if (inputHandler != null) {
				inputHandler.stopProcessing();
				inputHandler = null;
			}

			socket.close();
		}
		notifyListenerOfConnectionEvent(false); //TODO: (Nad) Executor..?

//		synchronized (closedSync) {
//			if (closed) {
//				throw new ChromeCastException("Channel already closed.");
//			} else {
//				closed = true;
//				notifyListenerOfConnectionEvent(false);
//				if (pingTimer != null) {
//					pingTimer.cancel();
//				}
//				if (inputHandler != null) {
//					inputHandler.stopProcessing();
//				}
//				if (socket != null) {
//					socket.close();
//				}
//			}
//		}
	}

	public boolean isClosed() {
		synchronized (socketLock) {
			return socket == null || socket.isClosed() || !socket.isConnected();
		}
	}

	/**
	 * Establishes a connection to the "cast device".
	 *
	 * @throws IOException
	 * @throws KeyManagementException
	 * @throws NoSuchAlgorithmException
	 */
	public void oldConnect() throws IOException, KeyManagementException, NoSuchAlgorithmException { //TODO: (Nad) Remove
//		synchronized (closedSync) {
//			if (socket == null || socket.isClosed()) {
//				SSLContext sc = SSLContext.getInstance("SSL");
//				sc.init(null, new TrustManager[] {new X509TrustAllManager()}, new SecureRandom());
//				socket = sc.getSocketFactory().createSocket();
//				socket.connect(address);
//			}
//			// Authenticate.
//			CastChannel.DeviceAuthMessage authMessage = CastChannel.DeviceAuthMessage.newBuilder()
//				.setChallenge(CastChannel.AuthChallenge.newBuilder().build())
//				.build();
//
//			CastMessage msg = CastMessage.newBuilder()
//				.setDestinationId(DEFAULT_RECEIVER_ID)
//				.setNamespace("urn:x-cast:com.google.cast.tp.deviceauth")
//				.setPayloadType(CastMessage.PayloadType.BINARY)
//				.setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
//				.setSourceId(name)
//				.setPayloadBinary(authMessage.toByteString())
//				.build();
//
//			write(msg);
//			CastMessage response = readMessage(socket.getInputStream()); //TODO: (Nad) Sync
//			CastChannel.DeviceAuthMessage authResponse = CastChannel.DeviceAuthMessage.parseFrom(response.getPayloadBinary());
//			if (authResponse.hasError()) {
//				throw new ChromeCastException("Authentication failed: " + authResponse.getError().getErrorType().toString());
//			}
//
//			// Send 'PING' message
//			PingTask pingTask = new PingTask(); //TODO: (Nad) Why ping before connect..?
////			pingTask.run();
//
//			// Send 'CONNECT' message to start session
//			write("urn:x-cast:com.google.cast.tp.connection", StandardMessage.connect(), DEFAULT_RECEIVER_ID);
//
//			// Start ping/pong and reader thread
//			pingTimer = new Timer(name + " PING");
//			pingTimer.schedule(pingTask, 1000, PING_PERIOD);
//
////			reader = new ReadThread();
//			inputHandler = new InputHandler(socket.getInputStream(), jsonMapper); //TODO: (Nad) Sync
//			inputHandler.start();
//
//			if (closed) {
//				closed = false;
//				notifyListenerOfConnectionEvent(true);
//			}
//		}
	}

	@SuppressWarnings("unchecked")
	private <T extends StandardResponse> T sendStandard(
		String namespace,
		StandardRequest message,
		String destinationId
	) throws IOException {
		return send(namespace, message, destinationId, (Class<T>) StandardResponse.class);
	}

	private <T extends Response> T send(
		String namespace,
		Request message,
		String destinationId,
		Class<T> responseClass
	) throws IOException {
		// Try to reconnect
		if (isClosed()) {
			try {
				oldConnect();
			} catch (GeneralSecurityException gse) {
				throw new ChromeCastException("Unexpected security exception", gse);
			}
		}

		Long requestId = requestCounter.getAndIncrement();
		message.setRequestId(requestId);
		if (!requestId.equals(message.getRequestId())) {
			throw new IllegalStateException("Request Id getter/setter contract violation"); //TODO: (Nad) Runtime.. bad?
		}

		if (responseClass == null) {
			write(namespace, message, destinationId);
			return null;
		}

		ResultProcessor<T> rp = new ResultProcessor<>(responseClass);
		requests.put(requestId, rp);

		write(namespace, message, destinationId);
		try {
			T response = rp.get();
			if (response instanceof StandardResponse.InvalidResponse) {
				StandardResponse.InvalidResponse invalid = (StandardResponse.InvalidResponse) response;
				throw new ChromeCastException("Invalid request: " + invalid.getReason());
			} else if (response instanceof StandardResponse.LoadFailedResponse) {
				throw new ChromeCastException("Unable to load media");
			} else if (response instanceof StandardResponse.LaunchErrorResponse) {
				StandardResponse.LaunchErrorResponse launchError = (StandardResponse.LaunchErrorResponse) response;
				throw new ChromeCastException("Application launch error: " + launchError.getReason());
			}
			return response;
		} catch (InterruptedException e) {
			throw new ChromeCastException("Interrupted while waiting for response", e);
		} catch (TimeoutException e) {
			throw new ChromeCastException("Waiting for response timed out", e);
		} finally {
			requests.remove(requestId);
		}
	}

	private void write(String namespace, Message message, String destinationId) throws IOException {
		write(namespace, jsonMapper.writeValueAsString(message), destinationId);
	}

	private void write(String namespace, String message, String destinationId) throws IOException {
		LOGGER.debug(CHROMECAST_API_MARKER, " s-> {}", message);
		CastMessage msg = CastMessage.newBuilder()
			.setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
			.setSourceId(senderId)
			.setDestinationId(destinationId)
			.setNamespace(namespace)
			.setPayloadType(CastMessage.PayloadType.STRING)
			.setPayloadUtf8(message)
			.build();
		write(msg);
	}

	private void write(CastMessage message) throws IOException {
		OutputStream os = socket.getOutputStream();
		writeB32Int(message.getSerializedSize(), os);
		message.writeTo(os);
	}

	private static CastMessage readMessage(InputStream inputStream) throws IOException {
		int size = readB32Int(inputStream);
		byte[] buf = new byte[size];
		int read = 0;
		while (read < size) {
			int readNow = inputStream.read(buf, read, buf.length - read);
			if (readNow == -1) {
				throw new ChromeCastException("Incomplete message, ended after reading " + read + " of " + size + " bytes");
			}
			read += readNow;
		}
		return CastMessage.parseFrom(buf);
	}

	private void notifyListenerOfConnectionEvent(final boolean connected) {
		if (this.eventListener != null) {
			this.eventListener.deliverConnectionEvent(connected);
		}
	}

	private void notifyListenersOfSpontaneousEvent(JsonNode json) throws JsonProcessingException {
		if (this.eventListener != null) {
			this.eventListener.deliverEvent(json);
		}
	}

	private void notifyListenersCustomMessageEvent(CustomMessageEvent event) {
		if (this.eventListener != null) {
			this.eventListener.deliverAppEvent(event);
		}
	}

	public Status getStatus() throws IOException {
		StandardResponse.StatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.receiver",
			StandardRequest.status(),
			DEFAULT_RECEIVER_ID
		);
		return status == null ? null : status.getStatus();
	}

	public boolean isAppAvailable(String appId) throws IOException {
		StandardResponse.AppAvailabilityResponse availability = sendStandard(
			"urn:x-cast:com.google.cast.receiver",
			StandardRequest.appAvailability(appId),
			DEFAULT_RECEIVER_ID
		);
		return availability != null && "APP_AVAILABLE".equals(availability.getAvailability().get(appId));
	}

	public Status launch(String appId) throws IOException {
		StandardResponse.StatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.receiver",
			StandardRequest.launch(appId),
			DEFAULT_RECEIVER_ID
		);
		return status == null ? null : status.getStatus();
	}

	public Status stop(String sessionId) throws IOException {
		StandardResponse.StatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.receiver",
			StandardRequest.stop(sessionId),
			DEFAULT_RECEIVER_ID
		);
		return status == null ? null : status.getStatus();
	}

	private void startSession(String destinationId) throws IOException {
		if (!sessions.contains(destinationId)) {
			write("urn:x-cast:com.google.cast.tp.connection", StandardMessage.connect(), destinationId);
			sessions.add(destinationId);
		}
	}

	public MediaStatus load(String destinationId, String sessionId, Media media, boolean autoplay, double currentTime,
		Map<String, String> customData) throws IOException {
		startSession(destinationId);
		StandardResponse.MediaStatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.media",
			StandardRequest.load(sessionId, media, autoplay, currentTime, customData),
			destinationId
		);
		return status == null || status.getStatuses().isEmpty() ? null : status.getStatuses().get(0);
	}

	public MediaStatus play(String destinationId, String sessionId, long mediaSessionId) throws IOException {
		startSession(destinationId);
		StandardResponse.MediaStatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.media",
			StandardRequest.play(sessionId, mediaSessionId),
			destinationId
		);
		return status == null || status.getStatuses().isEmpty() ? null : status.getStatuses().get(0);
	}

	public MediaStatus pause(String destinationId, String sessionId, long mediaSessionId) throws IOException {
		startSession(destinationId);
		StandardResponse.MediaStatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.media",
			StandardRequest.pause(sessionId, mediaSessionId),
			destinationId
		);
		return status == null || status.getStatuses().isEmpty() ? null : status.getStatuses().get(0);
	}

	public MediaStatus seek(String destinationId, String sessionId, long mediaSessionId, double currentTime) throws IOException {
		startSession(destinationId);
		StandardResponse.MediaStatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.media",
			StandardRequest.seek(sessionId, mediaSessionId, currentTime),
			destinationId
		);
		return status == null || status.getStatuses().isEmpty() ? null : status.getStatuses().get(0);
	}

	public Status setVolume(Volume volume) throws IOException {
		StandardResponse.StatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.receiver",
			StandardRequest.setVolume(volume),
			DEFAULT_RECEIVER_ID
		);
		return status == null ? null : status.getStatus();
	}

	public MediaStatus getMediaStatus(String destinationId) throws IOException {
		startSession(destinationId);
		StandardResponse.MediaStatusResponse status = sendStandard(
			"urn:x-cast:com.google.cast.media",
			StandardRequest.status(),
			destinationId
		);
		return status == null || status.getStatuses().isEmpty() ? null : status.getStatuses().get(0);
	}

	public <T extends Response> T sendGenericRequest(
		String destinationId,
		String namespace,
		Request request,
		Class<T> responseClass
	) throws IOException {
		startSession(destinationId);
		return send(namespace, request, destinationId, responseClass);
	}

	public void setRequestTimeout(long requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	protected static boolean isCustomMessage(@Nullable JsonNode parsedMessage) {
		if (parsedMessage == null) {
			return true;
		}
		if (parsedMessage.has("responseType")) {
			String parsedType = parsedMessage.get("responseType").asText();
			for (JsonSubTypes.Type type : STANDARD_RESPONSE_TYPES) {
				if (type.name().equals(parsedType)) {
					return false;
				}
			}
		}
		return !parsedMessage.has("requestId");
	}

	protected static Executor createExecutor() {
		ThreadPoolExecutor result = new ThreadPoolExecutor(
			0,
			Integer.MAX_VALUE,
			300L,
			TimeUnit.SECONDS,
			new SynchronousQueue<Runnable>(true),
			new ThreadFactory() {

				private final AtomicInteger threadNumber = new AtomicInteger(1);

				@Override
				public Thread newThread(Runnable r) {
					return new Thread(r, "ChromeCast API worker #" + threadNumber.getAndIncrement());
				}
			}
		);
		return result;
	}

	protected class PingTask extends TimerTask {

		private final String messageString;
		private final CastMessage message;

		public PingTask() {
			try {
				messageString = jsonMapper.writeValueAsString(StandardMessage.ping());
			} catch (JsonProcessingException e) {
				throw new AssertionError("Couldn't generate JSON for 'PING' message");
			}
			message = CastMessage.newBuilder()
				.setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
				.setSourceId(senderId)
				.setDestinationId(DEFAULT_RECEIVER_ID)
				.setNamespace("urn:x-cast:com.google.cast.tp.heartbeat")
				.setPayloadType(CastMessage.PayloadType.STRING)
				.setPayloadUtf8(messageString)
				.build();
		}

		@Override
		public void run() {
			if (LOGGER.isTraceEnabled(CHROMECAST_API_MARKER)) {
				LOGGER.trace(CHROMECAST_API_MARKER, "Pinging {}", remoteName);
			}
			try {
				write(message);
			} catch (IOException e) {
				LOGGER.warn(
					CHROMECAST_API_MARKER,
					"An error occurred while sending 'PING' to {}: {}",
					remoteName,
					e.getMessage()
				);
				LOGGER.trace(CHROMECAST_API_MARKER, "", e);
			}
		}
	}

	protected class InputHandler extends Thread {

		private volatile boolean running; //TODO: (Nad) Temp test

		@Nonnull
		private final InputStream is;

		@Nonnull
		private final CastMessage pongMessage;

		public InputHandler(@Nonnull InputStream inputStream) {
			super(remoteName + " input handler");
			if (inputStream == null) {
				throw new IllegalArgumentException("inputStream cannot be null");
			}
			this.is = inputStream;

			String messageString;
			try {
				messageString = jsonMapper.writeValueAsString(StandardMessage.pong());
			} catch (JsonProcessingException e) {
				throw new AssertionError("Couldn't generate JSON for 'PONG' message");
			}
			pongMessage = CastMessage.newBuilder()
				.setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
				.setSourceId(senderId)
				.setDestinationId(DEFAULT_RECEIVER_ID)
				.setNamespace("urn:x-cast:com.google.cast.tp.heartbeat")
				.setPayloadType(CastMessage.PayloadType.STRING)
				.setPayloadUtf8(messageString)
				.build();
			this.running = true;
		}

		@Override
		public void run() {
			String jsonMessage;
			CastMessage message;
			PayloadType payloadType;
			try {
				while (running) { //TODO: (Nad) Figure out
					try {
						message = readMessage(is);
					} catch (SocketTimeoutException e) {
						if (running) {
							LOGGER.debug(
								CHROMECAST_API_MARKER,
								"{} InputHandler: Received an unexpected SocketTimeoutException - attempting to resume: {}",
								remoteName,
								e.getMessage()
							);
						continue;
						} else {
							break;
						}
					}
					if (message != null && (payloadType = message.getPayloadType()) != null) {
						switch (payloadType) {
							case BINARY:
								LOGGER.trace(
									CHROMECAST_API_MARKER,
									"{} InputHandler: Received a message with binary payload ({} bytes)",
									remoteName,
									message.getPayloadBinary() == null ? "unknown number of" : message.getPayloadBinary().size()
								);
								CustomMessageEvent event = new CustomMessageEvent(message.getNamespace(), message.getPayloadBinary());
								notifyListenersCustomMessageEvent(event);
								break;
							case STRING:
								jsonMessage = message.getPayloadUtf8();
								if (Util.isBlank(jsonMessage)) {
									LOGGER.trace(
										CHROMECAST_API_MARKER,
										"{} InputHandler: Received an empty string message - ignoring",
										remoteName
									);
									continue;
								}
								jsonMessage = jsonMessage.replaceFirst("\"type\"", "\"responseType\"");
								if ("urn:x-cast:com.google.cast.tp.heartbeat".equals(message.getNamespace())) {
									// Deal with PING/PONG directly
									JsonNode parsedMessage = jsonMapper.readTree(jsonMessage);
									JsonNode tmpNode = parsedMessage.get("responseType");
									String responseType = tmpNode == null ? "" : tmpNode.asText("");
									if ("PING".equals(responseType)) {
										LOGGER.trace(
											CHROMECAST_API_MARKER,
											"Received PING from {}, replying with PONG",
											remoteName
										);
										write(pongMessage);
									} else if ("PONG".equals(responseType)) {
										LOGGER.trace(CHROMECAST_API_MARKER, "Received PONG from {}", remoteName);
									} else {
										LOGGER.trace(
											CHROMECAST_API_MARKER,
											"Received unexpected heartbeat message of type \"{}\" from {}",
											responseType,
											remoteName
										);
									}
									continue;
								}
								LOGGER.trace(
									CHROMECAST_API_MARKER,
									"{} InputHandler: Received a string message \"{}\"",
									remoteName,
									jsonMessage
								);
								EXECUTOR.execute(new StringMessageHandler(message, jsonMessage));
								break;
							default:
								LOGGER.warn(
									CHROMECAST_API_MARKER,
									"{} InputHandler: Received a message with an unknown payload type '{}'",
									remoteName,
									payloadType
								);
								break;

						}
					} else if (message != null) {
						LOGGER.warn(
							CHROMECAST_API_MARKER,
							"{} InputHandler: Received a message without a payload type",
							remoteName
						);
					} else {
						LOGGER.warn(
							CHROMECAST_API_MARKER,
							"{} InputHandler: Received a null message",
							remoteName
						);
					}
				}
			} catch (IOException e) {
				if (running) {
					LOGGER.error(CHROMECAST_API_MARKER, "{} InputHandler exception, terminating handler: ", remoteName, e.getMessage());
					LOGGER.trace(CHROMECAST_API_MARKER, "", e);
					running = false;
				} else {
					LOGGER.trace(
						CHROMECAST_API_MARKER,
						"Exception while shutting down {} InputHandler: {}",
						remoteName,
						e.getMessage()
					);
				}
				try {
					close();
				} catch (IOException ioe) {
					LOGGER.trace(CHROMECAST_API_MARKER, "An error occurred while closing socket: {}", e.getMessage());
				}
			}
		}

		public void stopProcessing() {
			running = false;
		}
	}

	protected class StringMessageHandler implements Runnable { //TODO: (Nad) Temp test, name, move etc

		@Nonnull
		protected final CastMessage message;

		@Nonnull
		protected final String jsonMessage;

		public StringMessageHandler(@Nonnull CastMessage message, @Nonnull String jsonMessage) {
			if (message == null) {
				throw new IllegalArgumentException("message cannot be null");
			}
			if (Util.isBlank(jsonMessage)) {
				throw new IllegalArgumentException("jsonMessage cannot be blank");
			}
			this.message = message;
			this.jsonMessage = jsonMessage;
		}

		@Override
		public void run() {
			try {
				LOGGER.error(CHROMECAST_API_MARKER, "Parsing message: {}", jsonMessage); //TODO: (Nad) Temp test
				JsonNode parsedMessage = jsonMapper.readTree(jsonMessage);
				String responseType;
				long requestId;
				if (parsedMessage == null) {
					responseType = "";
					requestId = -1L;
				} else {
					JsonNode tmpNode = parsedMessage.get("responseType");
					responseType = tmpNode == null ? "" : tmpNode.asText("");
					tmpNode = parsedMessage.get("requestId");
					requestId = tmpNode == null ? -1L : tmpNode.asLong(-1L);
				}
				ResultProcessor<? extends Response> resultProcessor;
				if (requestId > 0L && (resultProcessor = requests.remove(requestId)) != null) { //TODO: (Nad) Sync
					resultProcessor.process(jsonMessage); //TODO: (Nad) Sync
				} else if (parsedMessage == null || isCustomMessage(parsedMessage)) {
					//TODO: (Nad) Send "custom" event
					CustomMessageEvent event = new CustomMessageEvent(message.getNamespace(), message.getPayloadUtf8());
					notifyListenersCustomMessageEvent(event);
				} else if ("MEDIA_STATUS".equals(responseType)) {
					notifyListenersOfSpontaneousEvent(parsedMessage); //TODO: (Nad) Fix event
				} else { //TODO: (Nad) Figure out "CLOSE" message
					notifyListenersOfSpontaneousEvent(parsedMessage); //TODO: (Nad) Handle event
				}
			} catch (JsonProcessingException e) {
				LOGGER.warn(
					CHROMECAST_API_MARKER,
					"Error while processing JSON message from {}: {}",
					remoteName,
					e.getMessage()
				);
				LOGGER.trace(CHROMECAST_API_MARKER, "", e);
			}
		}
	}

	protected class ReadThread extends Thread { //TODO: (Nad) Remove

		private volatile boolean stop;

		@Override
		public void run() {
			while (!stop) {
				JsonNode parsed = null;
				String jsonMSG = null;
				CastMessage message = null;

				try {
					message = readMessage(socket.getInputStream());
					if (message.getPayloadType() == CastMessage.PayloadType.STRING) {
						LOGGER.debug(CHROMECAST_API_MARKER, " <-- {}", message.getPayloadUtf8());
						jsonMSG = message.getPayloadUtf8().replaceFirst("\"type\"", "\"responseType\"");
						if (jsonMSG == null || jsonMSG.isEmpty()) {
							LOGGER.warn(CHROMECAST_API_MARKER, " <-- Received empty message. Ignore.");
							continue;
						}

						// Determine whether the message belongs to cast
						// protocol or is a custom
						// message from the receiver app
						parsed = jsonMapper.readTree(jsonMSG);
					} else {
						LOGGER.warn(CHROMECAST_API_MARKER, "Received unexpected {} message", message.getPayloadType());
					}
				} catch (InvalidProtocolBufferException e) {
					warn("Error while processing protobuf", e);
				} catch (JsonProcessingException e) {
					warn("Error while processing json", e);
				} catch (IOException e) {
					if (stop) {
						LOGGER.debug(CHROMECAST_API_MARKER, "Got IOException while reading due to stream being closed (stop=true)", e);
						continue;
					}
					warn("Error while reading", e);
					String temp;
					if (message != null && message.getPayloadUtf8() != null) { //TODO: (Nad) Implement similar logic..?
						temp = message.getPayloadUtf8();
					} else {
						temp = " null payload in message ";
					}
					LOGGER.warn(CHROMECAST_API_MARKER, " <-- {}", temp);
					try {
						close();
					} catch (IOException ex) {
						warn("Error while closing channel", ex);
					}
				} catch (Exception e) {
					warn("Unknown error while reading", e);
					continue;
				}

				try {
					if (message == null) {
						continue;
					}

					if (parsed == null || isCustomMessage(parsed)) {
						CustomMessageEvent event = new CustomMessageEvent(message.getNamespace(), message.getPayloadUtf8());
						notifyListenersCustomMessageEvent(event);
					} else {
						if (parsed.has("requestId")) {
							Long requestId = parsed.get("requestId").asLong();
							final ResultProcessor<? extends Response> rp = requests.remove(requestId);
							if (rp != null) {
								rp.process(jsonMSG);
							} else {
								notifyListenersOfSpontaneousEvent(parsed);
							}
						} else if (parsed.has("responseType") && parsed.get("responseType").asText().equals("MEDIA_STATUS")) {
							notifyListenersOfSpontaneousEvent(parsed);
						} else if (parsed.has("responseType") && parsed.get("responseType").asText().equals("PING")) {
							write("urn:x-cast:com.google.cast.tp.heartbeat", StandardMessage.pong(), DEFAULT_RECEIVER_ID);
						} else if (parsed.has("responseType") && parsed.get("responseType").asText().equals("CLOSE")) {
							notifyListenersOfSpontaneousEvent(parsed);
						}
					}
				} catch (Exception e) {
					warn("Error while handling", e);
				}
			}
		}
	}

	private class ResultProcessor<T extends Response> {

		private final Class<T> responseClass;
		private T result;

		private ResultProcessor(Class<T> responseClass) {
			if (responseClass == null) {
				throw new NullPointerException();
			}
			this.responseClass = responseClass;
		}

		public void process(String jsonMSG) throws JsonMappingException, JsonProcessingException {
			synchronized (this) {
				this.result = jsonMapper.readValue(jsonMSG, responseClass);
				this.notify();
			}
		}

		public T get() throws InterruptedException, TimeoutException {
			synchronized (this) {
				if (result != null) {
					return result;
				}
				this.wait(requestTimeout);
				if (result == null) {
					throw new TimeoutException();
				}
				return result;
			}
		}
	}
}
