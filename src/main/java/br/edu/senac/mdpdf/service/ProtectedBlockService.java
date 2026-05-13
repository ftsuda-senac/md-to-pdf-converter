package br.edu.senac.mdpdf.service;

import br.edu.senac.mdpdf.config.MdToPdfProperties;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Renderiza o conteúdo de um bloco {@code :::protected} como imagem PNG base64.
 *
 * <p><strong>Pipeline:</strong>
 * <ol>
 *   <li>Markdown do bloco → HTML (Flexmark).</li>
 *   <li>HTML → PDF em memória (OpenHTMLtoPDF via {@link PdfService}).</li>
 *   <li>PDF → BufferedImage rasterizado (PDFBox) com recorte do espaço em branco.</li>
 *   <li>BufferedImage → PNG base64 para embedar no HTML principal.</li>
 * </ol>
 *
 * <p>Usar o mesmo renderizador (OpenHTMLtoPDF) para o bloco protegido e para o
 * documento principal garante fidelidade visual idêntica. A imagem resultante não
 * pode ser selecionada nem copiada no leitor de PDF.
 */
@Service
public class ProtectedBlockService {

	private static final Logger log = LoggerFactory.getLogger(ProtectedBlockService.class);

	/** CSS mínimo para a página isolada do bloco protegido. */
	private static final String PROTECTED_CSS = """
		@page {
			size: A4;
			margin: 0;

			pre, table, blockquote {
				page-break-inside: avoid;
			}
		}
		body {
			font-family: NotoSans, NotoEmoji, Helvetica, Arial, sans-serif;
			font-size: 14px;
			line-height: 1.5;
			color: #24292e;
			background-color: #ffffff;
			border-left: 4px solid #f59e0b;
			padding: 0;
			margin: 0;
		}
		h1, h2, h3, h4 { margin-top: 16px; margin-bottom: 8px; font-weight: 600; }
		h1 { font-size: 1.4em; }
		h2 { font-size: 1.2em; }
		h3 { font-size: 1.1em; }
		p  { margin-top: 0; margin-bottom: 10px; }
		code {
			font-family: NotoSansMono, "Courier New", Courier, monospace;
			font-size: 90%;
			background-color: rgba(27,31,35,0.07);
			padding: 2px 5px;
			border-radius: 3px;
		}
		pre {
			background-color: #f6f8fa;
			border-radius: 3px;
			padding: 12px;
			font-size: 90%;
			font-family: NotoSansMono, "Courier New", Courier, monospace;
			line-height: 1.45;
			overflow: auto;
			margin-bottom: 12px;
		}
		pre code { background: none; padding: 0; font-size: 100%; }
		blockquote {
			border-left: 3px solid #dfe2e5;
			color: #6a737d;
			padding: 0 12px;
			margin: 0 0 12px 0;
		}
		ul, ol { padding-left: 24px; margin-bottom: 12px; }
		table { border-collapse: collapse; width: 100%; margin-bottom: 12px; }
		th { background-color: #f6f8fa; font-weight: 600; }
		th, td { border: 1px solid #dfe2e5; padding: 5px 10px; }
		.hljs-keyword, .hljs-selector-tag { color: #d73a49; }
		.hljs-string, .hljs-attr          { color: #032f62; }
		.hljs-comment                     { color: #6a737d; font-style: italic; }
		.hljs-number, .hljs-literal       { color: #005cc5; }
		.hljs-type                        { color: #6f42c1; }
		.hljs-meta                        { color: #e36209; }
		""";

	private final PdfService pdfService;
	private final MdToPdfProperties properties;

	/** Parser/Renderer Flexmark compartilhados (thread-safe após construção). */
	private final Parser parser;
	private final HtmlRenderer renderer;

	public ProtectedBlockService(PdfService pdfService, MdToPdfProperties properties) {
		this.pdfService = pdfService;
		this.properties = properties;

		MutableDataSet opts = new MutableDataSet();
		this.parser = Parser.builder(opts).build();
		this.renderer = HtmlRenderer.builder(opts).build();
	}

	/**
	 * Converte o markdown do bloco protegido em uma string PNG base64.
	 *
	 * @param markdownContent conteúdo interno do bloco {@code :::protected}.
	 * @return string base64 do PNG, pronta para usar em {@code <img src="data:image/png;base64,...">}.
	 */
	public String renderToBase64(String markdownContent) {
		log.debug("Renderizando bloco protegido ({} chars)", markdownContent.length());

		String innerHtml    = renderer.render(parser.parse(markdownContent));
		String highlighted  = SyntaxHighlighter.applyToFragment(innerHtml);
		String fullHtml     = wrapInProtectedPage(highlighted);
		byte[] pdfBytes  = pdfService.renderToBytes(fullHtml);

		BufferedImage image = rasterizeAndStack(pdfBytes);
		return encodeToBase64(image);
	}

	// -------------------------------------------------------------------------
	// Privados
	// -------------------------------------------------------------------------

	private String wrapInProtectedPage(String innerHtml) {
		return """
			<!DOCTYPE html>
			<html>
			<head>
			<meta charset="UTF-8"/>
			<style>%s</style>
			</head>
			<body>%s</body>
			</html>
			""".formatted(PROTECTED_CSS, innerHtml);
	}

	/**
	 * Rasteriza todas as páginas do PDF e as empilha verticalmente.
	 * A última página é recortada para remover espaço em branco ao final.
	 */
	private BufferedImage rasterizeAndStack(byte[] pdfBytes) {
		try (PDDocument document = PDDocument.load(pdfBytes)) {
			PDFRenderer pdfRenderer = new PDFRenderer(document);
			int pageCount = document.getNumberOfPages();

			if (pageCount == 1) {
				BufferedImage page = pdfRenderer.renderImageWithDPI(0, properties.imageDpi());
				return cropTrailingWhitespace(page);
			}

			// Múltiplas páginas: empilhar verticalmente
			List<BufferedImage> pages = new ArrayList<>(pageCount);
			int totalHeight = 0;
			int maxWidth    = 0;

			for (int i = 0; i < pageCount; i++) {
				BufferedImage page = pdfRenderer.renderImageWithDPI(i, properties.imageDpi());
				if (i == pageCount - 1) {
					page = cropTrailingWhitespace(page);
				}
				pages.add(page);
				totalHeight += page.getHeight();
				maxWidth = Math.max(maxWidth, page.getWidth());
			}

			BufferedImage stacked = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
			Graphics2D g2d = stacked.createGraphics();
			int offsetY = 0;
			for (BufferedImage page : pages) {
				g2d.drawImage(page, 0, offsetY, null);
				offsetY += page.getHeight();
			}
			g2d.dispose();
			return stacked;

		} catch (IOException e) {
			throw new RuntimeException("Falha ao rasterizar PDF do bloco protegido", e);
		}
	}

	/**
	 * Remove linhas totalmente brancas (≥ 248 em RGB) do final da imagem,
	 * preservando uma margem de 16px abaixo do último conteúdo.
	 */
	private BufferedImage cropTrailingWhitespace(BufferedImage image) {
		int width  = image.getWidth();
		int height = image.getHeight();
		int lastContentRow = 20; // mínimo de altura

		outer:
		for (int y = height - 1; y >= 0; y--) {
			for (int x = 0; x < width; x++) {
				int rgb = image.getRGB(x, y);
				int r = (rgb >> 16) & 0xff;
				int g = (rgb >> 8)  & 0xff;
				int b =  rgb        & 0xff;
				if (r < 248 || g < 248 || b < 248) {
					lastContentRow = y;
					break outer;
				}
			}
		}

		int croppedHeight = Math.min(lastContentRow + 16, height);
		return image.getSubimage(0, 0, width, croppedHeight);
	}

	private String encodeToBase64(BufferedImage image) {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			ImageIO.write(image, "PNG", baos);
			return Base64.getEncoder().encodeToString(baos.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException("Falha ao codificar imagem em base64", e);
		}
	}
}
