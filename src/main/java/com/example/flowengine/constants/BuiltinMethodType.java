package com.example.flowengine.constants;

public enum BuiltinMethodType {
    RANDOM_NUMBER,   // params: min, max → method.result
    RANDOM_UUID,     // no params → method.result
    TIMESTAMP,       // params: format (optional, default ISO) → method.result
    STRING_CONCAT,   // params: values (comma-separated list) → method.result
    DB_QUERY         // params: connectionString, username, password, query → method.<columnName> per column
}