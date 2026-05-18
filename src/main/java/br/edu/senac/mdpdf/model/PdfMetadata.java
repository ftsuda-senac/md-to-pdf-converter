package br.edu.senac.mdpdf.model;

/**
 * Metadados incorporados no documento PDF gerado.
 *
 * <p>Os campos são preenchidos a partir de:
 * <ol>
 *   <li>Frontmatter YAML no início do arquivo .md ({@code ---} ... {@code ---}).</li>
 *   <li>Primeiro heading {@code # } do documento (somente para {@code title}).</li>
 *   <li>Nome base do arquivo (somente para {@code title}).</li>
 *   <li>Propriedade {@code mdpdf.author} (somente para {@code author}).</li>
 * </ol>
 *
 * <p>Exemplo de frontmatter:
 * <pre>
 * ---
 * title: Aula 03 — Dados Pessoais
 * author: Fernando Tsuda
 * subject: Banco de Dados II — TADS
 * ---
 * </pre>
 */
public record PdfMetadata(String title, String author, String subject) {

    /** Retorna uma cópia com o author substituído, mantendo os demais campos. */
    public PdfMetadata withAuthor(String newAuthor) {
        return new PdfMetadata(title, newAuthor, subject);
    }
}
