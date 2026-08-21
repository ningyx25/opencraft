package com.swaydy.opencraft.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 构建 OpenAI function-calling 的 parameters JSON Schema 的小工具。
 */
final class ToolSchema {
	private ToolSchema() {
	}

	/** 参数定义：properties 里的单项（type + description）。 */
	static JsonObject prop(String type, String description) {
		JsonObject obj = new JsonObject();
		obj.addProperty("type", type);
		obj.addProperty("description", description);
		return obj;
	}

	/** 构造 {type:object, properties:{...}, required:[...]}。 */
	static JsonObject object(JsonObject properties, String... required) {
		JsonObject obj = new JsonObject();
		obj.addProperty("type", "object");
		obj.add("properties", properties);
		if (required.length > 0) {
			JsonArray req = new JsonArray();
			for (String r : required) {
				req.add(r);
			}
			obj.add("required", req);
		}
		return obj;
	}
}
