//! Turns `cmd app_function list-app-functions` output into functions with JSON Schemas.
//!
//! The command prints AppSearch documents: every scalar is wrapped in a one-element array, and object types defined
//! by an app live in its `AppFunctionComponentMetadataDocument` under `dataTypes`, referenced by name.

use serde_json::{Map, Value, json};
use std::collections::HashMap;

/// One AppFunction as the system publishes it.
#[derive(Debug, Clone)]
pub struct Function {
    pub package: String,
    /// `functionId` verbatim; `execute-app-function --function` takes exactly this.
    pub id: String,
    pub description: String,
    /// JSON Schema of the parameters object.
    pub input_schema: Value,
    pub enabled_by_default: bool,
}

impl Function {
    /// The method part of the id: `createAlarm` for `com.urbandroid.sleep.functions.AppFunctions#createAlarm`.
    pub fn method(&self) -> &str {
        self.id.rsplit('#').next().unwrap_or(&self.id)
    }
}

// AppFunctionDataTypeMetadata type codes (androidx.appfunctions.metadata).
const TYPE_UNIT: i64 = 0;
const TYPE_BOOLEAN: i64 = 1;
const TYPE_BYTES: i64 = 2;
const TYPE_OBJECT: i64 = 3;
const TYPE_DOUBLE: i64 = 4;
const TYPE_FLOAT: i64 = 5;
const TYPE_LONG: i64 = 6;
const TYPE_INT: i64 = 7;
const TYPE_STRING: i64 = 8;
const TYPE_ARRAY: i64 = 10;
const TYPE_REFERENCE: i64 = 11;
const TYPE_ALL_OF: i64 = 12;
const TYPE_PENDING_INTENT: i64 = 13;

/// References nest; past this depth a type is emitted as a bare object (also breaks cycles).
const MAX_DEPTH: usize = 8;

fn first<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    match v.get(key)? {
        Value::Array(a) => a.first(),
        other => Some(other),
    }
}

fn first_str<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    first(v, key)?.as_str()
}

fn list<'a>(v: &'a Value, key: &str) -> &'a [Value] {
    v.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[])
}

/// Parses the whole listing. Unknown shapes are skipped, never fatal: one odd app must not hide the others.
pub fn parse(listing: &Value) -> Vec<Function> {
    let mut out = Vec::new();
    let Some(packages) = listing.as_object() else { return out };
    for (package, docs) in packages {
        let docs = docs.as_array().map(Vec::as_slice).unwrap_or(&[]);
        let mut components: HashMap<String, Value> = HashMap::new();
        let mut statics = Vec::new();
        for doc in docs {
            let Some(doc) = doc.as_object() else { continue };
            for (kind, body) in doc {
                if kind.starts_with("AppFunctionComponentMetadataDocument") {
                    for dt in list(body, "dataTypes") {
                        if let (Some(name), Some(meta)) = (first_str(dt, "name"), first(dt, "dataTypeMetadata")) {
                            components.insert(name.to_string(), meta.clone());
                        }
                    }
                } else if kind.starts_with("AppFunctionStaticMetadata") {
                    statics.push(body);
                }
            }
        }
        for s in statics {
            let Some(id) = first_str(s, "functionId") else { continue };
            let description = first_str(s, "description")
                .or_else(|| first_str(s, "schemaName"))
                .unwrap_or("")
                .trim()
                .to_string();
            out.push(Function {
                package: package.clone(),
                id: id.to_string(),
                description,
                input_schema: parameters_schema(s, &components),
                enabled_by_default: first(s, "enabledByDefault").and_then(Value::as_bool).unwrap_or(true),
            });
        }
    }
    out.sort_by(|a, b| (&a.package, &a.id).cmp(&(&b.package, &b.id)));
    out
}

fn parameters_schema(s: &Value, components: &HashMap<String, Value>) -> Value {
    let mut props = Map::new();
    let mut required = Vec::new();
    for p in list(s, "parameters") {
        let Some(name) = first_str(p, "name") else { continue };
        let mut schema = first(p, "dataTypeMetadata").map(|dt| type_schema(dt, components, 0)).unwrap_or(json!({}));
        if let Some(d) = first_str(p, "description") {
            schema["description"] = json!(d.trim());
        }
        if first(p, "isRequired").and_then(Value::as_bool).unwrap_or(false) {
            required.push(json!(name));
        }
        props.insert(name.to_string(), schema);
    }
    json!({ "type": "object", "properties": props, "required": required })
}

/// JSON Schema for one data type.
pub fn type_schema(dt: &Value, components: &HashMap<String, Value>, depth: usize) -> Value {
    let mut schema = match first(dt, "type").and_then(Value::as_i64) {
        Some(TYPE_UNIT) => json!({ "type": "null" }),
        Some(TYPE_BOOLEAN) => json!({ "type": "boolean" }),
        Some(TYPE_BYTES) => json!({ "type": "string", "contentEncoding": "base64" }),
        Some(TYPE_DOUBLE | TYPE_FLOAT) => json!({ "type": "number" }),
        Some(TYPE_LONG | TYPE_INT) => json!({ "type": "integer" }),
        Some(TYPE_STRING) => {
            let mut s = json!({ "type": "string" });
            let values = list(dt, "enumValues");
            if !values.is_empty() {
                s["enum"] = json!(values);
            }
            if let Some(f) = first_str(dt, "format") {
                s["format"] = json!(f);
            }
            s
        }
        Some(TYPE_ARRAY) => {
            let items = first(dt, "itemType").map(|i| type_schema(i, components, depth + 1)).unwrap_or(json!({}));
            json!({ "type": "array", "items": items })
        }
        Some(TYPE_OBJECT) => object_schema(dt, components, depth),
        Some(TYPE_REFERENCE) => match first_str(dt, "dataTypeReference").and_then(|r| components.get(r)) {
            Some(target) if depth < MAX_DEPTH => type_schema(target, components, depth + 1),
            _ => json!({ "type": "object" }),
        },
        Some(TYPE_ALL_OF) => {
            // Merge the parts into one object: more clients understand that than `allOf`.
            let mut props = Map::new();
            let mut required = Vec::new();
            for part in list(dt, "allOf") {
                let part = if part.get("type").is_some() { type_schema(part, components, depth + 1) } else { object_schema(part, components, depth + 1) };
                if let Some(p) = part.get("properties").and_then(Value::as_object) {
                    props.extend(p.clone());
                }
                if let Some(r) = part.get("required").and_then(Value::as_array) {
                    required.extend(r.iter().cloned());
                }
            }
            json!({ "type": "object", "properties": props, "required": required })
        }
        Some(TYPE_PENDING_INTENT) => json!({ "description": "Android PendingIntent; not representable over MCP" }),
        _ => json!({}),
    };
    if let Some(d) = first_str(dt, "description") {
        schema["description"] = json!(d.trim());
    }
    schema
}

fn object_schema(dt: &Value, components: &HashMap<String, Value>, depth: usize) -> Value {
    if depth >= MAX_DEPTH {
        return json!({ "type": "object" });
    }
    let mut props = Map::new();
    for p in list(dt, "properties") {
        let Some(name) = first_str(p, "name") else { continue };
        let schema = first(p, "dataTypeMetadata").map(|t| type_schema(t, components, depth + 1)).unwrap_or(json!({}));
        props.insert(name.to_string(), schema);
    }
    // `required` holds plain names, not one-element arrays.
    let required: Vec<Value> = list(dt, "required").to_vec();
    json!({ "type": "object", "properties": props, "required": required })
}
