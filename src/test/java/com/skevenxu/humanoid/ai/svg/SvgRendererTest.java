package com.skevenxu.humanoid.ai.svg;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SvgRendererTest {

    private final SvgRenderer renderer = new SvgRenderer(200_000);

    @Test
    void rendersSvgToPng() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">
                  <rect width="200" height="200" fill="#f5f5f5"/>
                  <circle cx="100" cy="100" r="60" fill="#e74c3c"/>
                  <text x="100" y="110" text-anchor="middle" font-size="24" fill="#fff">Hi</text>
                </svg>
                """;
        byte[] png = renderer.render(svg, 300, 300);

        assertThat(png.length).isGreaterThan(1000);
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }

    @Test
    void rejectsDangerousContent() {
        SvgRenderer r = renderer;
        assertThatThrownBy(() -> r.render("<svg><script>alert(1)</script></svg>", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.render("<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><svg></svg>", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.render("<svg><rect onload=\"x\" width=\"1\" height=\"1\"/></svg>", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.render("<svg><image href=\"http://evil/x.png\"/></svg>", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.render("not svg", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
