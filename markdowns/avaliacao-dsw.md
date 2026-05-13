# Avaliação — Desenvolvimento de Software Web
**Disciplina:** DSW | **Turma:** TADS 2026-1 | **Data:** 08/05/2026

---

## Instruções

- Duração: **90 minutos**.
- Consulta: **não permitida**.
- Questões objetivas valem 1,0 ponto cada; dissertativas valem conforme indicado.

---

## Questão 1 — HTTP e REST (1,0 pt)

Qual código de status HTTP deve ser retornado quando um recurso é **criado com sucesso**
via `POST /api/v1/users`?

- [ ] 200 OK
- [ ] 201 Created
- [ ] 204 No Content
- [ ] 400 Bad Request

:::protected
## Gabarito — Questão 1

**Resposta correta: 201 Created**

O RFC 7231 especifica que `201 Created` indica que a requisição foi atendida e
resultou na criação de um ou mais novos recursos. O cabeçalho `Location` deve
apontar para o URI do recurso criado.

Erros comuns:
- **200 OK** é usado em operações de leitura ou atualização bem-sucedidas.
- **204 No Content** indica sucesso sem corpo de resposta (comum em `DELETE`).
:::

---

## Questão 2 — Spring Boot (1,0 pt)

Analise o trecho abaixo e identifique o problema:

```java
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    @GetMapping("/{id}")
    public Product findById(@PathVariable Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new RuntimeException("Not found"));
    }
}
```

Marque a alternativa correta:

- [ ] A) O método deveria retornar `ResponseEntity<Product>`.
- [ ] B) `RuntimeException` não é a exceção adequada para recurso não encontrado.
- [ ] C) `@PathVariable` deveria ser `@RequestParam`.
- [ ] D) Tanto A quanto B estão corretos.

:::protected
## Gabarito — Questão 2

**Resposta correta: D) Tanto A quanto B estão corretos**

### Problema 1 — Tipo de retorno
Retornar `Product` diretamente não permite controlar o status HTTP. O correto é:

```java
return ResponseEntity.ok(product);
// ou
return ResponseEntity.notFound().build();
```

### Problema 2 — Exceção genérica
`RuntimeException` resulta em `500 Internal Server Error`. Deve-se usar uma
exceção de domínio anotada com `@ResponseStatus(HttpStatus.NOT_FOUND)` ou
tratada em um `@ControllerAdvice`.

```java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```
:::

---

## Questão 3 — Dissertativa (2,0 pts)

Explique o papel da anotação `@Transactional` no Spring e cite **dois cenários**
em que sua ausência causaria problemas.

*(Espaço para resposta)*

___

___

___

:::protected
## Gabarito — Questão 3 (Critérios de avaliação)

| Critério | Pontos |
|---|---|
| Definição correta de transação (atomicidade, rollback) | 0,5 |
| Menção ao contexto de persistência / session do JPA | 0,5 |
| Cenário 1 válido (ex.: múltiplas operações no banco) | 0,5 |
| Cenário 2 válido (ex.: LazyInitializationException) | 0,5 |

### Resposta esperada

`@Transactional` delimita um **contexto transacional**: todas as operações
executadas dentro do método são tratadas como uma unidade atômica — em caso
de exceção não verificada, o Spring executa `rollback` automaticamente.

**Cenário 1 — Múltiplas gravações atômicas:**
Ao criar um `Pedido` e seus `ItensPedido` em serviços diferentes, a ausência
de `@Transactional` pode gravar o pedido mas falhar nos itens, deixando o
banco em estado inconsistente.

**Cenário 2 — LazyInitializationException:**
Sem `@Transactional` no service, o `EntityManager` é fechado antes do
controller acessar coleções lazy (`@OneToMany`), lançando
`LazyInitializationException` em tempo de serialização.
:::

---

*Boa sorte!*
