package site.siyoung.beforeaibe.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// AI가 만든 unified diff를 저장 전에 검사함. 구조가 깨졌으면 거부하고, 헌크 줄 수만 틀렸으면 본문 기준으로 다시 씀.
// 저장소를 보지 않으므로 헌크 시작 줄이 실제 파일과 맞는지는 확인하지 못함(웹쉘이 적용할 때 판단)
public final class UnifiedDiffNormalizer {

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)$");

    private UnifiedDiffNormalizer() {
    }

    public static String normalize(String diff) {
        boolean trailingNewline = diff.endsWith("\n");
        String[] lines = diff.split("\n", -1);
        int length = trailingNewline ? lines.length - 1 : lines.length;

        List<String> out = new ArrayList<>();
        boolean fileHeader = false;
        boolean anyChange = false;
        boolean metadataChange = false;
        int i = 0;
        while (i < length) {
            String line = lines[i];
            Matcher hunk = HUNK.matcher(line);
            if (!hunk.matches()) {
                if (line.startsWith("+++ ")) {
                    fileHeader = true;
                } else if (!line.isBlank() && !isHeaderLine(line)) {
                    throw new InvalidDiffException("헌크 밖의 알 수 없는 줄: " + i);
                }
                metadataChange |= isMetadataChange(line);
                out.add(line);
                i++;
                continue;
            }
            if (!fileHeader) {
                throw new InvalidDiffException("파일 헤더(---/+++) 없는 헌크: " + i);
            }
            i++;
            List<String> body = new ArrayList<>();
            int oldCount = 0;
            int newCount = 0;
            while (i < length && !endsHunk(lines, i, length)) {
                String bodyLine = lines[i];
                // 헌크 끝의 빈 줄은 파일 사이 구분이므로 문맥으로 세지 않음
                if (bodyLine.isBlank() && blankUntilHunkEnd(lines, i, length)) {
                    break;
                }
                // 모델이 빈 문맥 줄의 공백을 지우는 경우가 많음. CRLF 파일의 줄바꿈은 유지함
                if (bodyLine.isEmpty() || bodyLine.equals("\r")) {
                    bodyLine = " " + bodyLine;
                }
                switch (bodyLine.charAt(0)) {
                    case ' ' -> {
                        oldCount++;
                        newCount++;
                    }
                    case '-' -> {
                        oldCount++;
                        anyChange = true;
                    }
                    case '+' -> {
                        newCount++;
                        anyChange = true;
                    }
                    case '\\' -> {
                    }
                    default -> throw new InvalidDiffException("헌크 본문의 알 수 없는 줄: " + i);
                }
                body.add(bodyLine);
                i++;
            }
            out.add("@@ -%s,%d +%s,%d @@%s".formatted(hunk.group(1), oldCount, hunk.group(2), newCount, hunk.group(3)));
            out.addAll(body);
        }
        if (!anyChange && !metadataChange) {
            throw new InvalidDiffException("변경 내용이 있는 헌크가 없음");
        }
        String joined = String.join("\n", out);
        return trailingNewline ? joined + "\n" : joined;
    }

    private static boolean isHeaderLine(String line) {
        return line.startsWith("--- ") || line.startsWith("diff ") || line.startsWith("index ")
                || line.startsWith("new file") || line.startsWith("deleted file") || line.startsWith("old mode")
                || line.startsWith("new mode") || line.startsWith("similarity ") || line.startsWith("dissimilarity ")
                || line.startsWith("rename ") || line.startsWith("copy ")
                || line.startsWith("Binary ");
    }

    // 내용 헌크 없이도 유효한 변경(이름·권한·파일 추가/삭제)
    private static boolean isMetadataChange(String line) {
        return line.startsWith("rename ") || line.startsWith("copy ") || line.startsWith("new mode")
                || line.startsWith("new file") || line.startsWith("deleted file");
    }

    private static boolean blankUntilHunkEnd(String[] lines, int i, int length) {
        int next = i;
        while (next < length && lines[next].isBlank()) {
            next++;
        }
        return next == length || endsHunk(lines, next, length);
    }

    // 줄 수를 믿을 수 없으니 다음 헌크·다음 파일 헤더가 나오면 끝으로 봄
    private static boolean endsHunk(String[] lines, int i, int length) {
        String line = lines[i];
        return HUNK.matcher(line).matches() || line.startsWith("diff ")
                || (line.startsWith("--- ") && i + 1 < length && lines[i + 1].startsWith("+++ "));
    }

    public static class InvalidDiffException extends RuntimeException {
        public InvalidDiffException(String message) {
            super(message);
        }
    }
}
