package com.example.flowengine.constants;

/**
 * The set of parameter types the method-builder UI can offer when a user defines a custom
 * method's parameters. This is descriptive metadata only — it tells the UI how to render the
 * input (text box, JSON editor, key-value list, etc.) and gives the user/LLM a hint about the
 * expected shape. It is NOT enforced at execution time: every parameter value still arrives in
 * the Groovy script's `params` map as whatever the caller sent (typically a String, or a
 * Map/List if the value was JSON). Script authors are responsible for parsing/casting
 * accordingly — see CustomMethodService for how values are bound into the Groovy binding.
 */
public enum ParameterType {
    STRING("String", "Plain text"),
    NUMBER("Number", "Integer or decimal value (use INTEGER/LONG/DOUBLE for a specific numeric type)"),
    INTEGER("Integer", "Whole number that fits in 32 bits"),
    LONG("Long", "Whole number that may exceed 32-bit range (e.g. timestamps, large IDs)"),
    DOUBLE("Double", "Decimal/floating-point number"),
    BOOLEAN("Boolean", "true / false"),
    JSON("JSON", "A JSON object or array, e.g. request body"),
    KEY_VALUE("Key Value", "A list of name/value pairs, e.g. headers"),
    LIST_STRING("List of Strings", "Comma-separated text values, e.g. \"a,b,c\""),
    LIST_NUMBER("List of Numbers", "Comma-separated numeric values, e.g. \"1,2,3\""),
    SECRET("Secret", "Sensitive value (password, token) — masked in the UI, encrypted at rest"),
    DATE("Date", "ISO-8601 date or date-time string");

    private final String label;
    private final String description;

    ParameterType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}