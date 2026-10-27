package cn.aigc.dit.server.workflow;

import cn.aigc.dit.common.exception.BizException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves canvas placeholders from ordered, node-local and upstream scopes. */
public final class WorkflowVariables {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");

    private WorkflowVariables() {
    }

    public record Scope(int priority, String source, Map<String, String> values) {
    }

    public record Resolution(String value, Map<String, String> values) {
    }

    public static Resolution resolve(String template, List<Scope> scopes) {
        return resolve(template, scopes, new java.util.LinkedHashSet<>());
    }

    private static Resolution resolve(String template, List<Scope> scopes, java.util.Set<String> visiting) {
        String input = template == null ? "" : template;
        Matcher matcher = TOKEN.matcher(input);
        Map<String, String> resolved = new LinkedHashMap<>();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (resolved.containsKey(name)) {
                continue;
            }
            List<Scope> available = scopes == null ? List.of() : scopes;
            int bestPriority = Integer.MAX_VALUE;
            for (Scope scope : available) {
                String candidate = scope.values() == null ? null : scope.values().get(name);
                if (candidate != null && !candidate.isBlank()) {
                    bestPriority = Math.min(bestPriority, scope.priority());
                }
            }
            String bestValue = null;
            for (Scope scope : available) {
                String candidate = scope.values() == null ? null : scope.values().get(name);
                if (candidate == null || candidate.isBlank() || scope.priority() != bestPriority) {
                    continue;
                }
                if (bestValue == null) {
                    bestValue = candidate;
                } else if (!candidate.equals(bestValue)) {
                    throw new BizException("变量「" + name + "」存在同级冲突");
                }
            }
            if (bestValue == null) {
                throw new BizException("缺少变量「" + name + "」");
            }
            if (!visiting.add(name)) {
                throw new BizException("变量「" + name + "」存在循环引用");
            }
            resolved.put(name, resolve(bestValue, scopes, visiting).value());
            visiting.remove(name);
        }
        matcher.reset();
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(resolved.get(matcher.group(1).trim())));
        }
        matcher.appendTail(output);
        return new Resolution(output.toString(), Collections.unmodifiableMap(new LinkedHashMap<>(resolved)));
    }
}
