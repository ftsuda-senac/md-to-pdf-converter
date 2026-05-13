package br.edu.senac.mdpdf.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

@Service
public class MermaidService {

	private static final Logger log = LoggerFactory.getLogger(MermaidService.class);
	private static final String DOCKER_IMAGE = "ghcr.io/mermaid-js/mermaid-cli/mermaid-cli";
	private static final String MERMAID_CONFIG = """
		{
		  "theme": "default",
		  "padding": 4,
		  "themeVariables": {
		    "fontSize": "11px"
		  }
		}
		""";

	/**
	 * Renders a Mermaid diagram source string to a PNG, returned as a base64 string.
	 * Requires Docker to be running on the host.
	 * Uses temp files with a volume mount — more reliable than stdin/stdout with Puppeteer.
	 */
	public String renderToBase64(String mermaidSource) {
		log.debug("Renderizando diagrama Mermaid ({} chars)", mermaidSource.length());
		Path tempDir = null;
		try {
			tempDir = Files.createTempDirectory("mermaid-");
			Path inputFile  = tempDir.resolve("input.mmd");
			Path outputFile = tempDir.resolve("output.png");

			Files.writeString(inputFile, mermaidSource, StandardCharsets.UTF_8);
			Files.writeString(tempDir.resolve("config.json"), MERMAID_CONFIG, StandardCharsets.UTF_8);

			String hostDir = tempDir.toAbsolutePath().toString();

			Process process = new ProcessBuilder(
				"docker", "run", "--rm",
				"-v", hostDir + ":/data",
				DOCKER_IMAGE,
				"-i", "/data/input.mmd",
				"-o", "/data/output.png",
				"-c", "/data/config.json"
			).start();

			// Drain stdout and stderr concurrently to avoid blocking
			ByteArrayOutputStream stderrBaos = new ByteArrayOutputStream();
			Thread stderrReader = Thread.ofVirtual().start(() -> {
				try { process.getErrorStream().transferTo(stderrBaos); } catch (IOException ignored) {}
			});
			process.getInputStream().transferTo(OutputStream.nullOutputStream());

			stderrReader.join();
			int exitCode = process.waitFor();

			if (exitCode != 0) {
				String err = stderrBaos.toString(StandardCharsets.UTF_8);
				throw new MermaidRenderException("mmdc falhou (exit " + exitCode + "): " + err);
			}
			if (!Files.exists(outputFile)) {
				throw new MermaidRenderException("mmdc não gerou o arquivo de saída");
			}

			byte[] pngBytes = Files.readAllBytes(outputFile);
			log.debug("Diagrama Mermaid renderizado: {} bytes PNG", pngBytes.length);
			return Base64.getEncoder().encodeToString(pngBytes);

		} catch (IOException e) {
			throw new MermaidRenderException("Falha ao invocar Docker para renderizar Mermaid", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MermaidRenderException("Renderização Mermaid interrompida", e);
		} finally {
			deleteTempDir(tempDir);
		}
	}

	private void deleteTempDir(Path tempDir) {
		if (tempDir == null) return;
		try {
			Files.walk(tempDir)
				.sorted(Comparator.reverseOrder())
				.forEach(p -> p.toFile().delete());
		} catch (IOException ignored) {}
	}

	public static class MermaidRenderException extends RuntimeException {
		public MermaidRenderException(String message) { super(message); }
		public MermaidRenderException(String message, Throwable cause) { super(message, cause); }
	}
}
