package br.edu.senac.mdpdf;

import br.edu.senac.mdpdf.config.MdToPdfProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MdToPdfProperties.class)
public class MdToPdfApplication {

	/**
	 * Define headless ANTES do Spring inicializar qualquer bean AWT/Swing.
	 * Necessário para renderizar imagens dos blocos protegidos em servidor.
	 */
	static {
		System.setProperty("java.awt.headless", "true");
	}

	public static void main(String[] args) {
		SpringApplication.run(MdToPdfApplication.class, args);
	}
}
