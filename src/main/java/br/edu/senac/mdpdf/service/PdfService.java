package br.edu.senac.mdpdf.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Responsável por renderizar HTML → PDF usando OpenHTMLtoPDF (baseado em PDFBox).
 *
 * <p>Dois modos de uso:
 * <ul>
 *   <li>{@link #renderToBytes(String)} — retorna o PDF em memória (usado para
 *       rasterizar blocos protegidos).</li>
 *   <li>{@link #renderToFile(String, Path)} — salva o PDF diretamente em disco.</li>
 * </ul>
 */
@Service
public class PdfService {

	private static final Logger log = LoggerFactory.getLogger(PdfService.class);

	/**
	 * Renderiza o HTML fornecido para PDF e retorna os bytes.
	 * Utilizado internamente pelo {@link ProtectedBlockService} para gerar a imagem
	 * do bloco protegido.
	 */
	public byte[] renderToBytes(String html) {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			build(html, baos);
			return baos.toByteArray();
		} catch (IOException e) {
			throw new PdfRenderException("Falha ao renderizar PDF em memória", e);
		}
	}

	/**
	 * Renderiza o HTML e salva o PDF no caminho especificado.
	 * Cria os diretórios pai se necessário.
	 */
	public void renderToFile(String html, Path destination) {
		try {
			Files.createDirectories(destination.getParent());
			try (OutputStream os = Files.newOutputStream(destination)) {
				build(html, os);
			}
			log.info("PDF gerado: {}", destination.toAbsolutePath());
		} catch (IOException e) {
			throw new PdfRenderException("Falha ao renderizar PDF para arquivo: " + destination, e);
		}
	}

	// -------------------------------------------------------------------------
	// Privado
	// -------------------------------------------------------------------------

	private void build(String html, OutputStream output) throws IOException {
		// OpenHTMLtoPDF usa parser XML estrito — é preciso converter o HTML5
		// (gerado pelo Flexmark) para XHTML antes de passar ao builder.
		String xhtml = toXhtml(html);

		PdfRendererBuilder builder = new PdfRendererBuilder();
		builder.useFastMode();
		builder.useFont(
			() -> getClass().getResourceAsStream("/fonts/NotoSans-Regular.ttf"),
			"NotoSans");
		builder.useFont(
			() -> getClass().getResourceAsStream("/fonts/NotoSans-Bold.ttf"),
			"NotoSans", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
		builder.useFont(
			() -> getClass().getResourceAsStream("/fonts/NotoEmoji-VariableFont_wght.ttf"),
			"NotoEmoji");
		builder.useFont(
			() -> getClass().getResourceAsStream("/fonts/NotoSansMono-VariableFont_wdth,wght.ttf"),
			"NotoSansMono");
		builder.useFont(
			() -> getClass().getResourceAsStream("/fonts/NotoSansMono-VariableFont_wdth,wght.ttf"),
			"NotoSansMono", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
		builder.withHtmlContent(xhtml, null);
		builder.toStream(output);
		builder.run();
	}

	/**
	 * Converte HTML5 arbitrário em XHTML serializado como XML.
	 * O Jsoup corrige tags não fechadas, auto-fecha void elements ({@code <br>},
	 * {@code <img>}, {@code <hr>}, etc.) e produz saída compatível com o
	 * parser XML do OpenHTMLtoPDF.
	 */
	private String toXhtml(String html) {
		Document doc = Jsoup.parse(html);
		doc.outputSettings()
			.syntax(Document.OutputSettings.Syntax.xml)
			.charset(StandardCharsets.UTF_8)
			.indentAmount(0);
		return doc.html();
	}

	// -------------------------------------------------------------------------
	// Exceção
	// -------------------------------------------------------------------------

	public static class PdfRenderException extends RuntimeException {
		public PdfRenderException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
