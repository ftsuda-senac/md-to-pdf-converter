package br.edu.senac.mdpdf.runner;

import br.edu.senac.mdpdf.config.MdToPdfProperties;
import br.edu.senac.mdpdf.service.MarkdownService;
import br.edu.senac.mdpdf.service.PdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ponto de entrada da conversão em lote.
 *
 * <p>Ao iniciar, varre o diretório {@code mdpdf.input-dir} em busca de
 * arquivos {@code *.md} (não recursivo), converte cada um para PDF e
 * salva em {@code mdpdf.output-dir} com o mesmo nome base e extensão
 * {@code .pdf}.
 *
 * <p>Erros em arquivos individuais são registrados mas não interrompem
 * o processamento dos demais.
 */
@Component
public class ConversionRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(ConversionRunner.class);

	private final MdToPdfProperties properties;
	private final MarkdownService markdownService;
	private final PdfService pdfService;

	public ConversionRunner(
		MdToPdfProperties properties,
		MarkdownService markdownService,
		PdfService pdfService
	) {
		this.properties = properties;
		this.markdownService = markdownService;
		this.pdfService = pdfService;
	}

	@Override
	public void run(String... args) {
		//Path inputDir  = properties.inputDir();
		Path inputDir = Path.of("E:\\senac\\tutorial-dados-pessoais");
		Path outputDir = properties.outputDir();

		log.info("=== md-to-pdf Converter ===");
		log.info("Entrada : {}", inputDir.toAbsolutePath());
		log.info("Saída   : {}", outputDir.toAbsolutePath());

		if (!Files.isDirectory(inputDir)) {
			log.error("Diretório de entrada não encontrado: {}", inputDir.toAbsolutePath());
			return;
		}

		List<Path> mdFiles = listMarkdownFiles(inputDir);

		if (mdFiles.isEmpty()) {
			log.warn("Nenhum arquivo .md encontrado em: {}", inputDir.toAbsolutePath());
			return;
		}

		log.info("{} arquivo(s) .md encontrado(s)", mdFiles.size());

		int success = 0;
		int failure = 0;
		List<String> errors = new ArrayList<>();

		for (Path mdFile : mdFiles) {
			try {
				convert(mdFile, outputDir);
				success++;
			} catch (Exception e) {
				failure++;
				String msg = "ERRO em %s: %s".formatted(mdFile.getFileName(), e.getMessage());
				errors.add(msg);
				log.error(msg, e);
			}
		}

		log.info("-----------------------------------");
		log.info("Concluído: {} OK, {} com erro", success, failure);
		if (!errors.isEmpty()) {
			errors.forEach(log::error);
		}
	}

	// -------------------------------------------------------------------------
	// Privados
	// -------------------------------------------------------------------------

	private void convert(Path mdFile, Path outputDir) throws IOException {
		log.info("Convertendo: {}", mdFile.getFileName());

		String markdown = Files.readString(mdFile, StandardCharsets.UTF_8);
		String html     = markdownService.toHtml(markdown);

		String pdfName  = replaceMdExtension(mdFile.getFileName().toString());
		Path destination = outputDir.resolve(pdfName);

		pdfService.renderToFile(html, destination);
	}

	private List<Path> listMarkdownFiles(Path dir) {
		try (Stream<Path> stream = Files.list(dir)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".md"))
				.sorted()
				.toList();
		} catch (IOException e) {
			log.error("Falha ao listar arquivos em: {}", dir, e);
			return List.of();
		}
	}

	private String replaceMdExtension(String filename) {
		if (filename.endsWith(".md")) {
			return filename.substring(0, filename.length() - 3) + ".pdf";
		}
		return filename + ".pdf";
	}
}
