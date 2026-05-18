package br.edu.senac.mdpdf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Configurações do conversor, mapeadas do application.yml (prefixo "mdpdf").
 *
 * <pre>
 * mdpdf:
 *   input-dir: /caminho/para/markdowns
 *   output-dir: /caminho/para/pdfs
 *   image-dpi: 144
 * </pre>
 */
@ConfigurationProperties(prefix = "mdpdf")
public record MdToPdfProperties(

	/** Diretório contendo os arquivos .md de entrada. */
	Path inputDir,

	/** Diretório onde os PDFs gerados serão salvos. */
	Path outputDir,

	/**
	 * DPI usado ao rasterizar blocos protegidos como imagem.
	 * 144 dpi = boa qualidade sem arquivo muito grande.
	 */
	@DefaultValue("144") int imageDpi,

	/**
	 * Autor padrão embutido nos metadados do PDF.
	 * Pode ser sobreescrito por frontmatter no arquivo .md ({@code author: ...}).
	 * Se vazio, o campo author não é preenchido.
	 */
	@DefaultValue("") String author
) {}
