# Coding Guidelines

## Java Style & Imports
- **Avoid Fully Qualified Names (FQNs)**: Never use package-qualified type names inline in code (e.g., use `Multi<T>` instead of `io.smallrye.mutiny.Multi<T>`). Always add the corresponding `import` statement at the top of the file, unless there is an unavoidable naming collision in the same file.
- **Import Nested Types Directly**: When referencing static nested classes, records, enums, or interfaces from other types (such as `Map.Entry`, `HttpResponse.BodyHandlers`, or service DTOs like `DraftService.DraftResult`), import the nested type directly (e.g., `import java.util.Map.Entry;`) and use its simple name (`Entry`) in code whenever possible.
