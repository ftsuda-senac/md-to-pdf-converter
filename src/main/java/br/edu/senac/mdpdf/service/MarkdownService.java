package br.edu.senac.mdpdf.service;

import br.edu.senac.mdpdf.model.PdfMetadata;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converte um arquivo Markdown em uma página HTML completa pronta para
 * ser entregue ao {@link PdfService}.
 *
 * <h2>Fluxo de processamento</h2>
 * <ol>
 *   <li>Detecta blocos {@code :::protected ... :::} via regex.</li>
 *   <li>Para cada bloco, delega ao {@link ProtectedBlockService} a geração de
 *       uma imagem PNG base64.</li>
 *   <li>Substitui cada bloco por uma tag {@code <img>} com a imagem embedada.</li>
 *   <li>Parseia o Markdown restante (agora com tags {@code <img>} raw) usando
 *       Flexmark com extensões GFM.</li>
 *   <li>Envolve o HTML em um template com a folha de estilos GitHub.</li>
 * </ol>
 *
 * <h2>Exemplo de bloco protegido no .md</h2>
 * <pre>
 * :::protected
 * ## Gabarito — Questão 3
 *
 * A resposta correta é **B**, pois o Spring Boot auto-configura o DataSource
 * quando detecta `spring-boot-starter-data-jpa` no classpath.
 * :::
 * </pre>
 */
@Service
public class MarkdownService {

	private static final Logger log = LoggerFactory.getLogger(MarkdownService.class);

	/**
	 * Detecta blocos delimitados por {@code :::protected} (linha própria) e
	 * {@code :::} (linha própria). DOTALL para capturar múltiplas linhas.
	 */
	private static final Pattern PROTECTED_BLOCK =
		Pattern.compile("^:::protected\\r?\\n(.*?)\\r?\\n^:::\\s*$",
			Pattern.DOTALL | Pattern.MULTILINE);

	/** Frontmatter YAML delimitado por {@code ---} no início do arquivo. */
	private static final Pattern FRONTMATTER =
		Pattern.compile("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", Pattern.DOTALL);

	/** Primeiro heading H1 do conteúdo markdown. */
	private static final Pattern FIRST_H1 =
		Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

	/** Detecta blocos de código Mermaid: ```mermaid ... ``` em linhas próprias. */
	private static final Pattern MERMAID_BLOCK =
		Pattern.compile("^```mermaid\\r?\\n(.*?)\\r?\\n^```\\s*$",
			Pattern.DOTALL | Pattern.MULTILINE);

	/** Extensões Flexmark equivalentes ao GitHub Flavored Markdown. */
	private static final MutableDataSet FLEXMARK_OPTIONS = new MutableDataSet()
		.set(Parser.EXTENSIONS, List.of(
			TablesExtension.create(),
			StrikethroughExtension.create(),
			AutolinkExtension.create(),
			TaskListExtension.create()
		))
		// GitHub trata newline simples como quebra de linha
		.set(HtmlRenderer.SOFT_BREAK, "<br />\n")
		.set(TablesExtension.COLUMN_SPANS, false)
		.set(TablesExtension.APPEND_MISSING_COLUMNS, true)
		.set(TablesExtension.DISCARD_EXTRA_COLUMNS, true);

	private final Parser parser = Parser.builder(FLEXMARK_OPTIONS).build();
	private final HtmlRenderer renderer = HtmlRenderer.builder(FLEXMARK_OPTIONS).build();

	private final ProtectedBlockService protectedBlockService;
	private final MermaidService mermaidService;
	private final String githubCss;

	public MarkdownService(ProtectedBlockService protectedBlockService, MermaidService mermaidService) {
		this.protectedBlockService = protectedBlockService;
		this.mermaidService = mermaidService;
		this.githubCss = loadResource("github.css");
	}

	/**
	 * Converte o conteúdo Markdown em uma string HTML completa (com DOCTYPE,
	 * {@code <head>} e {@code <body>}), pronta para o {@link PdfService}.
	 *
	 * @param markdownContent conteúdo bruto do arquivo .md.
	 * @return página HTML completa como string.
	 */
	/**
	 * Extrai metadados do frontmatter YAML e/ou do conteúdo markdown.
	 *
	 * @param markdownContent conteúdo bruto do arquivo .md.
	 * @param filenameHint    nome base do arquivo, usado como título de último recurso.
	 * @return metadados para embutir no PDF.
	 */
	public PdfMetadata extractMetadata(String markdownContent, String filenameHint) {
		if (markdownContent.startsWith("﻿")) markdownContent = markdownContent.substring(1);

		Map<String, String> fm = new HashMap<>();
		String body = markdownContent;

		Matcher fmMatcher = FRONTMATTER.matcher(markdownContent);
		if (fmMatcher.find()) {
			body = markdownContent.substring(fmMatcher.end());
			for (String line : fmMatcher.group(1).split("\\r?\\n")) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					fm.put(line.substring(0, colon).trim().toLowerCase(),
						   line.substring(colon + 1).trim());
				}
			}
		}

		String title = fm.getOrDefault("title", null);
		if (title == null) {
			Matcher h1 = FIRST_H1.matcher(body);
			title = h1.find() ? h1.group(1).trim() : filenameHint;
		}

		return new PdfMetadata(title, fm.getOrDefault("author", null), fm.getOrDefault("subject", null));
	}

	public String toHtml(String markdownContent) {
		if (markdownContent.startsWith("﻿")) markdownContent = markdownContent.substring(1);

		// Strip frontmatter so it doesn't appear in the rendered output
		Matcher fmMatcher = FRONTMATTER.matcher(markdownContent);
		if (fmMatcher.find()) markdownContent = markdownContent.substring(fmMatcher.end());

		// 1. Substituir blocos Mermaid por <img> de diagrama renderizado
		String withMermaid = replaceMermaidBlocks(markdownContent);

		// 2. Substituir :::protected ... ::: por <img> de imagem rasterizada
		String preprocessed = replaceProtectedBlocks(withMermaid);

		// 2. Flexmark: Markdown → fragmento HTML
		String bodyHtml = renderer.render(parser.parse(preprocessed));

		// 3. Aplicar syntax highlighting nos blocos de código
		String highlighted = highlightCodeBlocks(bodyHtml);

		// 4. Montar página HTML completa
		return buildPage(highlighted);
	}

	// -------------------------------------------------------------------------
	// Privados
	// -------------------------------------------------------------------------

	private String replaceMermaidBlocks(String markdown) {
		Matcher matcher = MERMAID_BLOCK.matcher(markdown);
		StringBuffer sb = new StringBuffer();
		int count = 0;

		while (matcher.find()) {
			count++;
			log.debug("Processando diagrama Mermaid #{}", count);

			String base64 = mermaidService.renderToBase64(matcher.group(1));
			String imgTag = "\n\n<img src=\"data:image/png;base64," + base64 + "\" " +
				"style=\"max-width:100%;display:block;margin-left:auto;margin-right:auto;page-break-inside:avoid;\" " +
				"alt=\"Diagrama Mermaid\"/>\n\n";

			matcher.appendReplacement(sb, Matcher.quoteReplacement(imgTag));
		}
		matcher.appendTail(sb);

		if (count > 0) {
			log.info("{} diagrama(s) Mermaid processado(s)", count);
		}
		return sb.toString();
	}

	/**
	 * Localiza todos os blocos protegidos, renderiza cada um como imagem e
	 * substitui pelo elemento {@code <img>} correspondente.
	 *
	 * <p>As tags {@code <img>} são colocadas em linhas isoladas para que o
	 * Flexmark as trate como blocos HTML e não as envolva em {@code <p>}.
	 */
	private String replaceProtectedBlocks(String markdown) {
		Matcher matcher = PROTECTED_BLOCK.matcher(markdown);
		StringBuffer sb = new StringBuffer();
		int count = 0;

		while (matcher.find()) {
			count++;
			log.debug("Processando bloco protegido #{}", count);

			String blockContent = matcher.group(1);
			String base64 = protectedBlockService.renderToBase64(blockContent);

			// Imagem com atributos que garantem:
			// - max-width: não ultrapassa a largura do conteúdo
			// - display:block: comportamento de bloco (sem inline)
			// - page-break-inside:avoid: não quebra entre páginas
			String imgTag = "\n\n<img src=\"data:image/png;base64," + base64 + "\" " +
				"style=\"max-width:100%;display:block;margin-left:auto;margin-right:auto;page-break-inside:avoid;\" " +
				"alt=\"Conteúdo protegido\"/>\n\n";

			matcher.appendReplacement(sb, Matcher.quoteReplacement(imgTag));
		}
		matcher.appendTail(sb);

		if (count > 0) {
			log.info("{} bloco(s) protegido(s) processado(s)", count);
		}
		return sb.toString();
	}

	private String highlightCodeBlocks(String html) {
		return SyntaxHighlighter.applyToFragment(html);
	}

	private String buildPage(String bodyHtml) {
		return """
			<!DOCTYPE html>
			<html lang="pt-BR">
			<head>
			<meta charset="UTF-8"/>
			<style>
			%s
			</style>
			</head>
			<body>
			<div class="markdown-body">
			%s
			</div>
			</body>
			</html>
			""".formatted(githubCss, bodyHtml);
	}

	private String loadResource(String filename) {
		try {
			ClassPathResource resource = new ClassPathResource(filename);
			return resource.getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Recurso não encontrado no classpath: " + filename, e);
		}
	}
}
