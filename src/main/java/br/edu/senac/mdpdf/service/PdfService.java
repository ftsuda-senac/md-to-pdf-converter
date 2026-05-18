package br.edu.senac.mdpdf.service;

import br.edu.senac.mdpdf.model.PdfMetadata;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 *       rasterizar blocos protegidos; sem PDF/A).</li>
 *   <li>{@link #renderToFile(String, Path, PdfMetadata)} — salva PDF/A-3b em disco.
 *       Os metadados são gravados no {@code PDDocumentInformation} <em>antes</em> de
 *       {@code createPDF()} ser chamado; o OpenHTMLtoPDF lê esses valores durante a
 *       geração do XMP, garantindo sincronização perfeita entre info dict e XMP —
 *       requisito obrigatório do padrão PDF/A.</li>
 * </ul>
 */
@Service
public class PdfService {

	private static final Logger log = LoggerFactory.getLogger(PdfService.class);

	/**
	 * Renderiza HTML para PDF em memória.
	 * Usado pelo {@link ProtectedBlockService} para rasterizar blocos protegidos;
	 * não precisa de PDF/A compliance.
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
	 * Renderiza HTML como PDF/A-3b e salva no destino especificado.
	 *
	 * <p>Metadados são gravados no {@code PDDocumentInformation} antes de
	 * {@code createPDF()} ser invocado. O OpenHTMLtoPDF lê title/author/subject
	 * do info dict durante a construção do XMP, produzindo um documento com
	 * info dict e XMP totalmente sincronizados sem nenhum pós-processamento.
	 */
	public void renderToFile(String html, Path destination, PdfMetadata metadata) {
		try {
			Files.createDirectories(destination.getParent());
			try (OutputStream os = Files.newOutputStream(destination)) {
				buildForFile(html, os, metadata);
			}
			log.info("PDF gerado: {}", destination.toAbsolutePath());
		} catch (IOException e) {
			throw new PdfRenderException("Falha ao renderizar PDF para arquivo: " + destination, e);
		}
	}

	// -------------------------------------------------------------------------
	// Privado
	// -------------------------------------------------------------------------

	/** Renderiza HTML → PDF sem PDF/A (usado para rasterização interna). */
	private void build(String html, OutputStream output) throws IOException {
		String xhtml = toXhtml(html);
		PdfRendererBuilder builder = new PdfRendererBuilder();
		builder.useFastMode();
		configureFonts(builder);
		builder.withHtmlContent(xhtml, null);
		builder.toStream(output);
		builder.run();
	}

	/**
	 * Renderiza HTML → PDF/A-3b gravando metadados no info dict antes de
	 * {@code createPDF()}.
	 *
	 * <p>O OpenHTMLtoPDF lê title/author/subject do {@code PDDocumentInformation}
	 * durante {@code finishPDF()} e os escreve na mesma passagem em que gera o XMP
	 * (pdfaid, AdobePDF, XMPBasic, DublinCore) — sem necessidade de pós-processamento.
	 */
	private void buildForFile(String html, OutputStream output, PdfMetadata metadata) throws IOException {
		String xhtml = toXhtml(html);

		byte[] iccBytes;
		try (InputStream icc = getClass().getResourceAsStream("/icc/sRGB.icc")) {
			if (icc == null) throw new IllegalStateException("ICC profile não encontrado: /icc/sRGB.icc");
			iccBytes = icc.readAllBytes();
		}

		PdfRendererBuilder builder = new PdfRendererBuilder();
		builder.useFastMode();
		builder.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_3_B);
		builder.useColorProfile(iccBytes);
		configureFonts(builder);
		builder.withHtmlContent(xhtml, null);
		builder.toStream(output);

		try (PdfBoxRenderer renderer = builder.buildPdfRenderer()) {
			renderer.layout();

			// Populate the info dict before createPDF() so OpenHTMLtoPDF
			// reads these values when building the XMP in finishPDF().
			PDDocumentInformation info = renderer.getPdfDocument().getDocumentInformation();
			if (metadata.title()   != null) info.setTitle(metadata.title());
			if (metadata.author()  != null) info.setAuthor(metadata.author());
			if (metadata.subject() != null) info.setSubject(metadata.subject());

			renderer.createPDF();
		}
	}

	private void configureFonts(PdfRendererBuilder builder) {
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
