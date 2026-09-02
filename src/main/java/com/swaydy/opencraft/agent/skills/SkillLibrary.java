package com.swaydy.opencraft.agent.skills;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 内置技能库：从 jar 资源 {@code skills/} 加载 SKILL.md 文档并渲染成 system 提示词片段。
 *
 * <p><b>资源布局</b>（jar 内打不进目录枚举,用索引文件列清单）：
 * <pre>
 * skills/index.json                  —— ["gather-wood", "craft-toolchain", ...]
 * skills/gather-wood/SKILL.md —— frontmatter(name/description/requires_tools) + 正文
 * </pre>
 *
 * <p><b>注入方式</b>：{@code promptsFragment(绑定列表, 可用工具集)} 渲染 {@code # Skills}
 * 大节（每个命中技能一个 {@code ##} 小节:description + 正文）,由 {@code Prompts.staticSystem}
 * 拼在 Capabilities 之后（静态 system,跨轮恒定以命中 KV 前缀缓存）。<b>绑定优先</b>：技能由预设（{@code AgentDefinition.skills}）点名
 * 才会注入——未绑定的技能不加载、不占上下文;绑定了还要过 {@code requires_tools}
 * （当前预设的可用工具全命中）——纯聊天预设即使绑了挖掘技能也收不到。内置技能都是
 * 人工精选的短文档,直接整体注入——
 * 游戏 Agent 无法按需读文件,progressive disclosure 退化为"按工具相关性整节注入"。
 *
 * <p>加载失败（索引缺失/单个文件损坏）只告警不抛出——技能是增强,不是依赖。
 * 纯 Java、无 Minecraft 依赖,可 JUnit 单测（主资源在测试 classpath 上）。
 */
public final class SkillLibrary {
	private static final Logger LOGGER = LoggerFactory.getLogger(SkillLibrary.class);

	/** 索引文件（资源根起）。 */
	static final String INDEX_RESOURCE = "skills/index.json";
	/** 单个技能文件的资源路径模板。 */
	static final String skillResource(String name) {
		return "skills/" + name + "/SKILL.md";
	}

	private static volatile List<Skill> builtIns;

	private SkillLibrary() {
	}

	/** 全部内置技能（懒加载一次;加载失败为空表,不缓存 null）。 */
	public static List<Skill> builtIns() {
		List<Skill> skills = builtIns;
		if (skills == null) {
			synchronized (SkillLibrary.class) {
				if (builtIns == null) {
					builtIns = load(SkillLibrary.class.getClassLoader());
				}
				skills = builtIns;
			}
		}
		return skills;
	}

	/**
	 * 渲染 system 提示词的 {@code # Skills} 片段：<b>双重过滤</b>——只渲染
	 * {@code boundSkills} 里绑定的、且对 {@code availableTools} 适用
	 * （{@code requires_tools} 全命中）的技能（每个一节:name 小节 + description + 正文）。
	 * 绑定在预设（AgentDefinition.skills）上,管理粒度是"哪个预设加载哪些技能";
	 * 未绑定的技能不注入、不占上下文。没有适用技能返回空串（调用方跳过拼接）。
	 */
	public static String promptsFragment(java.util.Collection<String> boundSkills,
	                                       Set<String> availableTools) {
		return render(builtIns(), boundSkills, availableTools);
	}

	/** 渲染核心（库可注入,便于单测空库/子集路径）。 */
	static String render(List<Skill> library, java.util.Collection<String> boundSkills,
	                       Set<String> availableTools) {
		if (boundSkills == null || boundSkills.isEmpty()) {
			return "";
		}
		java.util.Set<String> wanted = new java.util.HashSet<>(boundSkills);
		List<Skill> applicable = new ArrayList<>();
		for (Skill s : library) {
			if (wanted.contains(s.name()) && s.applicableTo(availableTools)) {
				applicable.add(s);
			}
		}
		if (applicable.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("# Skills");
		for (Skill s : applicable) {
			sb.append("\n\n## ").append(s.name()).append("\n\n")
					.append(s.description()).append("\n\n")
					.append(s.body());
		}
		return sb.toString();
	}

	/** 从 classpath 加载索引列出的全部技能;坏条目跳过并告警。 */
	static List<Skill> load(ClassLoader classLoader) {
		List<Skill> out = new ArrayList<>();
		String index = readResource(classLoader, INDEX_RESOURCE);
		if (index == null) {
			LOGGER.warn("[OpenCraft] 技能索引 {} 缺失,内置技能不注入", INDEX_RESOURCE);
			return out;
		}
		JsonArray names;
		try {
			JsonElement el = JsonParser.parseString(index);
			if (!el.isJsonArray()) {
				throw new IllegalArgumentException("index.json 必须是字符串数组");
			}
			names = el.getAsJsonArray();
		} catch (Exception e) {
			LOGGER.warn("[OpenCraft] 技能索引 {} 解析失败: {}", INDEX_RESOURCE, e.toString());
			return out;
		}
		for (JsonElement nameEl : names) {
			if (!nameEl.isJsonPrimitive()) {
				continue;
			}
			String name = nameEl.getAsString().trim();
			String markdown = readResource(classLoader, skillResource(name));
			Skill skill = markdown == null ? null : Skill.parse(markdown);
			if (skill == null) {
				LOGGER.warn("[OpenCraft] 内置技能 {} 加载失败（文件缺失或 frontmatter 非法）,已跳过", name);
				continue;
			}
			if (!skill.name().equals(name)) {
				LOGGER.warn("[OpenCraft] 内置技能目录名 {} 与 frontmatter name {} 不一致,已跳过", name, skill.name());
				continue;
			}
			LOGGER.info("[OpenCraft] 内置技能已加载: {}（requires_tools={}）",
					name, skill.requiresTools());
			out.add(skill);
		}
		return out;
	}

	private static String readResource(ClassLoader classLoader, String resource) {
		try (InputStream in = classLoader == null
				? ClassLoader.getSystemResourceAsStream(resource)
				: classLoader.getResourceAsStream(resource)) {
			if (in == null) {
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.warn("[OpenCraft] 读取技能资源 {} 失败: {}", resource, e.toString());
			return null;
		}
	}
}
