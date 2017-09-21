package hello;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import com.google.cloud.speech.v1.RecognitionAudio;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.RecognizeResponse;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.SpeechRecognitionResult;
import com.google.cloud.speech.v1.RecognitionConfig.AudioEncoding;
import com.google.protobuf.ByteString;

import hello.storage.StorageFileNotFoundException;
import hello.storage.StorageProperties;
import hello.storage.StorageService;

@Controller
public class FileUploadController {

	private final StorageService storageService;

	@Autowired
	public FileUploadController(StorageService storageService) {
		this.storageService = storageService;
	}

	@GetMapping("/")
	public String listUploadedFiles(Model model) throws IOException {

		model.addAttribute("files",
				storageService.loadAll()
						.map(path -> MvcUriComponentsBuilder
								.fromMethodName(FileUploadController.class, "serveFile", path.getFileName().toString())
								.build().toString())
						.collect(Collectors.toList()));

		return "uploadForm";
	}

	@GetMapping("/files/{filename:.+}")
	@ResponseBody
	public ResponseEntity<Resource> serveFile(@PathVariable String filename) {

		Resource file = storageService.loadAsResource(filename);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFilename() + "\"")
				.body(file);
	}

	@PostMapping("/")
	public String handleFileUpload(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes)
			throws Exception {

		storageService.store(file);

		// Phan tích và chuyển dữ liệu thành text
		SpeechClient speech = SpeechClient.create();

		String test = "ffmpeg -i " + System.getProperty("user.dir") + "\\upload-dir\\" + file.getOriginalFilename()
				+ " -acodec pcm_s16le -ar 16000 -ac 1 " + System.getProperty("user.dir") + "\\upload-dir\\"
				+ file.getOriginalFilename() + ".wav -y";
		Runtime rt = Runtime.getRuntime();
		rt.exec(test);
		Thread.sleep(3000);
		test = "ffmpeg -ss 0 -t 15 -i " + System.getProperty("user.dir") + "\\upload-dir\\" + file.getOriginalFilename()
				+ ".wav " + System.getProperty("user.dir") + "\\upload-dir\\" + file.getOriginalFilename() + ".flac -y";
		rt.exec(test);
		Thread.sleep(3000);
		
		Path path = Paths.get(System.getProperty("user.dir") + "\\upload-dir\\" + file.getOriginalFilename() + ".flac");//file.getOriginalFilename()
		byte[] data = Files.readAllBytes(path);

		ByteString audioBytes = ByteString.copyFrom(data);

		// Builds the sync recognize request
		RecognitionConfig config = RecognitionConfig.newBuilder().setEncoding(AudioEncoding.FLAC)
				.setSampleRateHertz(16000).setLanguageCode("vi-VN").build();
		RecognitionAudio audio = RecognitionAudio.newBuilder().setContent(audioBytes).build();

		// Performs speech recognition on the audio file
		RecognizeResponse response = speech.recognize(config, audio);
		List<SpeechRecognitionResult> results = response.getResultsList();

		// Connect to websocket server
		// http://192.168.9.67:8080/gs-guide-websocket

		List<Transport> transports = new ArrayList<>();
		transports.add(new WebSocketTransport(new StandardWebSocketClient()));
		transports.add(new RestTemplateXhrTransport());

		SockJsClient sockJsClient = new SockJsClient(transports);

		ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
		taskScheduler.afterPropertiesSet();

		String stompUrl = "http://192.168.9.67:8080/gs-guide-websocket/";
		WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
		stompClient.setMessageConverter(new StringMessageConverter());
		stompClient.setTaskScheduler(taskScheduler);
		stompClient.setDefaultHeartbeat(new long[] { 0, 0 });

		ProducerStompSessionHandler producer = null;

		for (SpeechRecognitionResult result : results) {
			// There can be several alternative transcripts for a given chunk of speech.
			// Just use the
			// first (most likely) one here.
			SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);
			producer = new ProducerStompSessionHandler(alternative.getTranscript());
			stompClient.connect(stompUrl, producer);
			stompClient.setTaskScheduler(taskScheduler);
			Thread.sleep(2000);
			JSONObject json = new JSONObject();
			json.put("content", alternative.getTranscript());
			System.out.println(json.toString());
			StompHeaders header = new StompHeaders();

			header.setContentType(new MimeType("application", "json"));
			header.setDestination("/app/hello");
			producer.session.send(header, json.toString().getBytes());
		}
		if (producer != null && producer.session != null) {
			producer.session.disconnect();
		}
		speech.close();

		redirectAttributes.addFlashAttribute("message",
				"You successfully uploaded " + file.getOriginalFilename() + "!");

		return "redirect:/";
	}

	@ExceptionHandler(StorageFileNotFoundException.class)
	public ResponseEntity<?> handleStorageFileNotFound(StorageFileNotFoundException exc) {
		return ResponseEntity.notFound().build();
	}

	private static class ProducerStompSessionHandler extends StompSessionHandlerAdapter {

		private StompSession session;

		public ProducerStompSessionHandler(String content) {
		}

		@Override
		public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
			this.session = session;
			try {
			} catch (Throwable t) {
			}
		}

		@Override
		public void handleTransportError(StompSession session, Throwable exception) {
			System.out.println("test");
		}

		@Override
		public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable ex) {
			System.out.println("test");
		}

		@Override
		public void handleFrame(StompHeaders headers, Object payload) {
			System.out.println("test");
		}
	}

}
