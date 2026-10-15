"""工具参数强校验（M4-4.1）：JSON Schema 校验，非法参数在**调用前**被拒。

为什么用 JSON Schema：模型输出的 ``arguments`` 是不可信输入，必须在执行任何业务动作前
做类型/必填/取值域校验；同时所有工具 schema 强制 ``additionalProperties=false``，
实现**字段白名单**，防止模型夹带未声明参数（如自行传入 tenant_id 造成越权）。
"""

from __future__ import annotations

import json
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

# 同时最多保留的 schema 校验器实例数（工具数量很少，够用）
_VALIDATOR_CACHE: dict[int, Draft202012Validator] = {}


class SchemaValidationError(ValueError):
    """参数不合法（JSON 非法 / 类型不符 / 缺必填 / 出现未声明字段）。"""


def build_validator(schema: dict[str, Any]) -> Draft202012Validator:
    """构建并缓存 JSON Schema 校验器（按 schema 对象身份缓存，避免重复解析）。"""
    if schema is None or not isinstance(schema, dict):
        raise SchemaValidationError("工具未声明参数 schema")
    cache_key = id(schema)
    validator = _VALIDATOR_CACHE.get(cache_key)
    if validator is None:
        try:
            Draft202012Validator.check_schema(schema)
        except SchemaError as exc:
            raise SchemaValidationError(f"工具 schema 非法：{exc.message}") from exc
        validator = Draft202012Validator(schema)
        _VALIDATOR_CACHE[cache_key] = validator
    return validator


def parse_arguments(raw_arguments: str) -> dict[str, Any]:
    """把模型给出的 arguments 字符串解析为 JSON 对象。"""
    text = (raw_arguments or "").strip() or "{}"
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise SchemaValidationError(f"参数不是合法 JSON：{exc}") from exc
    if not isinstance(payload, dict):
        raise SchemaValidationError("参数必须是 JSON 对象")
    return payload


def validate_arguments(schema: dict[str, Any], raw_arguments: str) -> dict[str, Any]:
    """解析并校验参数。

    Returns:
        校验通过的参数对象（可直接交给执行器）。

    Raises:
        SchemaValidationError: 参数非法（含可读的定位与原因，便于回填模型自我纠正）。
    """
    payload = parse_arguments(raw_arguments)
    validator = build_validator(schema)
    errors = sorted(validator.iter_errors(payload), key=lambda error: list(error.absolute_path))
    if errors:
        first = errors[0]
        location = ".".join(str(part) for part in first.absolute_path) or "根"
        raise SchemaValidationError(f"参数校验失败：字段 {location} {first.message}")
    return payload
