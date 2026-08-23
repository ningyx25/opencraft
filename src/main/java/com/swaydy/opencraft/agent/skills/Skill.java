package com.swaydy.opencraft.agent.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一个内置技能：一份遵循 SKILL.md 规范的 Markdown 文档（YAML frontmatter + 正文），
 * 教助手 Agent「在什么场景下、按什么步骤」完成一类任务。
 *
 * <p>形态对齐 ZCode/Claude 的 skill 约定（skill-creator 方法论）：
 * <ul>
 * <li>frontmatter {@code name}——小写 kebab-case 标识（与目录名一致）；</li>
 * <li>frontmatter {@code description}——触发信号：做什么 + 什么场景该用它
 *     （写给模型看,宁可选词"主动"一点防漏触发）；</li>
 * <li>frontmatter {@code requires_tools}（可选）——依赖的工具名列表
 *     （如 {@code player_mine}）,注入时按当前预设可用工具过滤——
 *     没有相应工具的预设（如纯聊天）不会收到挖掘类技能的噪音；</li>
 * <li>正文——祈使句步骤 + 解释为什么（模型理解原因才遵循得好）。 </li>
 * </ul>
 *
 * <p>纯 Java、无 Minecraft 依赖（解析与过滤逻辑均可 JUnit 单测）；
 * 加载见 {@link SkillLibrary}。
 */
public record Skill(String name, String description, String body, List<String> requiresTools) {

	/** name 的合法形状：小写字母/数字,用连字符分段,1~64 字符。 */
	private static final String NAME_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

	/** 该技能对「当前可用工具集」是否适用（无 requires_tools = 不限）。 */
	public boolean applicableTo(Set<String> availableTools) {
		if (requiresTools == null || requiresTools.isEmpty()) {
			return true;
		}
		if (availableTools == null) {
			return false;
		}
		return requiresTools.stream().allMatch(availableTools::contains);
	}

	/**
	 * 解析一份 SKILL.md 文本：首行 {@code ---} 起的 flat {@code key: value}
	 * frontmatter（到下一个 {@code ---} 行止）+ Markdown 正文。
	 * 非法（缺 name/description、name 形状不合规、frontmatter 损坏）返回 null，
	 * 调用方跳过该技能并告警——单个坏文件不拖垮整个技能库。
	 */
	public static Skill parse(String markdown) {
		if (markdown == null || markdown.isBlank()) {
			return null;
		}
		String[] front = splitFrontmatter(markdown);
		if (front == null) {
			return null;
		}
		String name = null;
		String description = null;
		List<String> requires = new ArrayList<>();
		for (String line : front[0].split("\n")) {
			int idx = line.indexOf(':');
			if (idx <= 0) {
				continue;
			}
			String key = line.substring(0, idx).trim();
			String value = line.substring(idx + 1).trim();
			switch (key) {
				case "name" -> name = value;
				case "description" -> description = value;
				case "requires_tools" -> requires.addAll(parseList(value));
				default -> { /* 保留字段之外的键忽略,向前兼容 */ }
			}
		}
		if (name == null || !name.matches(NAME_PATTERN) || name.length() > 64
				|| description == null || description.isBlank()) {
			return null;
		}
		return new Skill(name, description, front[1].trim(), List.copyOf(requires));
	}

	/** 拆出 [frontmatter 正文, markdown 正文];缺分隔标记返回 null。 */
	private static String[] splitFrontmatter(String markdown) {
		String normalized = markdown.replace("\r\n", "\n");
		if (!normalized.startsWith("---\n")) {
			return null;
		}
		int end = normalized.indexOf("\n---", 4);
		if (end < 0) {
			return null;
		}
		return new String[]{
				normalized.substring(4, end),
				normalized.substring(end + 4)};
	}

	/** 列表值解析：兼容 "a, b"、"[a, b]" 两种写法。 */
	private static List<String> parseList(String value) {
		List<String> out = new ArrayList<>();
		String v = value.trim();
		if (v.startsWith("[") && v.endsWith("]")) {
			v = v.substring(1, v.length() - 1);
		}
		for (String item : v.split(",")) {
			String s = item.trim();
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}
}
