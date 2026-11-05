package site.siyoung.beforeaibe.service;

import org.springframework.stereotype.Service;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.value.TaskContent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Plan 해시 (API 명세서 2.3): hashTarget을 RFC 8785(JCS)로 정규화한 UTF-8 바이트의 SHA-256.
// 웹 서비스 값은 화면 표시용이고 승인자 기기와 웹쉘이 각자 다시 계산하므로, 두 쪽과 같은 규칙으로 만들어야 함
@Service
public class PlanHashService {

    private static final String PREFIX = "sha256:";

    public String hash(Request request, Plan plan) {
        return hash(hashTarget(request, plan));
    }

    // hashTarget: requestId, planId, projectId, repository, baseCommit, tasks[{order, id, type, risk, target, content}]
    // 금지(FORBIDDEN) 작업은 실행 대상이 아니고 웹쉘 검증 ⑥(금지 규칙 미포함)에 걸리므로 해시 대상에서 뺌
    // 승인 전 원문 조회(3.6 approval-context)와 웹쉘 실행 요청(5.1)의 plan에도 같은 객체를 씀
    public Map<String, Object> hashTarget(Request request, Plan plan) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("requestId", request.getId().toString());
        target.put("planId", plan.getId().toString());
        target.put("projectId", request.getProjectId().toString());
        target.put("repository", plan.getRepository());
        target.put("baseCommit", plan.getBaseCommit());
        // JCS는 배열 순서를 바꾸지 않으므로 작업은 order 순으로 고정
        target.put("tasks", plan.getTasks().stream()
                .filter(task -> task.getRisk() != Risk.FORBIDDEN)
                .sorted(Comparator.comparingInt(Task::getOrder))
                .map(PlanHashService::taskTarget)
                .toList());
        return target;
    }

    public String hash(Map<String, Object> hashTarget) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(hashTarget).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // RFC 8785 정규화. hashTarget에는 객체·배열·문자열·정수만 들어가므로 그 범위만 다룸(실수는 쓰지 않음)
    public String canonicalize(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static Map<String, Object> taskTarget(Task task) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("order", task.getOrder());
        target.put("id", task.getId().toString());
        target.put("type", task.getType().name());
        target.put("risk", task.getRisk().name());
        target.put("target", task.getTarget());
        target.put("content", contentTarget(task.getContent()));
        return target;
    }

    // TaskContent는 null 필드를 저장·응답하지 않으므로(NON_NULL) 해시 대상에서도 뺌
    private static Map<String, Object> contentTarget(TaskContent content) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (content == null) {
            return target;
        }
        putIfNotNull(target, "diff", content.diff());
        putIfNotNull(target, "command", content.command());
        putIfNotNull(target, "query", content.query());
        putIfNotNull(target, "body", content.body());
        return target;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(s, out);
            case UUID uuid -> writeString(uuid.toString(), out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Map<?, ?> map -> writeObject(map, out);
            case List<?> list -> writeArray(list, out);
            default -> throw new IllegalArgumentException("해시 대상에 쓸 수 없는 값: " + value.getClass());
        }
    }

    // 키는 UTF-16 코드 단위 순으로 정렬 (String.compareTo가 같은 기준)
    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>();
        map.forEach((key, val) -> entries.add(Map.entry((String) key, val)));
        entries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        out.append('{');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(entries.get(i).getKey(), out);
            out.append(':');
            write(entries.get(i).getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(list.get(i), out);
        }
        out.append(']');
    }

    // JSON.stringify와 같은 이스케이프: ", \, 제어 문자만 이스케이프하고 나머지(한글 등)는 그대로 둠
    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
