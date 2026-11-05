package site.siyoung.beforeaibe.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnifiedDiffNormalizerTest {

    @Test
    void 헌크_줄_수가_틀리면_본문_기준으로_고친다() {
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1,9 +1,9 @@ head\n ctx\n-old\n+new\n+more\n";

        assertThat(UnifiedDiffNormalizer.normalize(diff))
                .isEqualTo("--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,3 @@ head\n ctx\n-old\n+new\n+more\n");
    }

    @Test
    void 빈_문맥_줄은_공백_문맥으로_보고_여러_파일을_나눈다() {
        String diff = "--- a/a\n+++ b/a\n@@ -1 +1 @@\n\n-x\n+y\n--- a/b\n+++ b/b\n@@ -1 +1 @@\n-p\n+q\n";

        assertThat(UnifiedDiffNormalizer.normalize(diff)).isEqualTo(
                "--- a/a\n+++ b/a\n@@ -1,2 +1,2 @@\n \n-x\n+y\n--- a/b\n+++ b/b\n@@ -1,1 +1,1 @@\n-p\n+q\n");
    }

    @Test
    void 파일_헤더_없는_헌크는_거부한다() {
        assertThatThrownBy(() -> UnifiedDiffNormalizer.normalize("@@ -1 +1 @@\n-a\n+b\n"))
                .isInstanceOf(UnifiedDiffNormalizer.InvalidDiffException.class);
    }

    @Test
    void 헌크_본문에_접두어_없는_줄이_있으면_거부한다() {
        assertThatThrownBy(() -> UnifiedDiffNormalizer.normalize("--- a/a\n+++ b/a\n@@ -1 +1 @@\nbroken\n"))
                .isInstanceOf(UnifiedDiffNormalizer.InvalidDiffException.class);
    }

    @Test
    void 변경_없는_diff는_거부한다() {
        assertThatThrownBy(() -> UnifiedDiffNormalizer.normalize("--- a/a\n+++ b/a\n"))
                .isInstanceOf(UnifiedDiffNormalizer.InvalidDiffException.class);
    }

    @Test
    void 파일_사이와_끝의_빈_줄은_문맥으로_세지_않는다() {
        String diff = "--- a/a\n+++ b/a\n@@ -1 +1 @@\n-x\n+y\n\n--- a/b\n+++ b/b\n@@ -1 +1 @@\n-p\n+q\n\n";

        assertThat(UnifiedDiffNormalizer.normalize(diff)).contains("@@ -1,1 +1,1 @@\n-x\n+y\n", "@@ -1,1 +1,1 @@\n-p\n+q\n");
    }

    @Test
    void 이름만_바꾼_diff는_통과한다() {
        String diff = "diff --git a/a b/b\nsimilarity index 100%\nrename from a\nrename to b\n";

        assertThat(UnifiedDiffNormalizer.normalize(diff)).isEqualTo(diff);
    }

    @Test
    void CRLF_문맥_줄의_줄바꿈을_유지한다() {
        String diff = "--- a/a\n+++ b/a\n@@ -1 +1 @@\n ctx\r\n-x\r\n+y\r\n";

        assertThat(UnifiedDiffNormalizer.normalize(diff)).contains(" ctx\r\n-x\r\n+y\r\n");
    }
}
