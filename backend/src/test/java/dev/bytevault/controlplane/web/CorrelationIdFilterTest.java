package dev.bytevault.controlplane.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesWellFormedIncomingId() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/health");
        req.addHeader(CorrelationIdFilter.HEADER, "abc-12345-def");
        var res = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                seenInMdc.set(CorrelationIdFilter.current());
            }
        });

        assertThat(res.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-12345-def");
        assertThat(seenInMdc.get()).isEqualTo("abc-12345-def");
        assertThat(CorrelationIdFilter.current()).as("MDC cleared after request").isNull();
    }

    @Test
    void replacesMalformedIdToPreventLogInjection() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/health");
        req.addHeader(CorrelationIdFilter.HEADER, "bad\nvalue injected");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        String id = res.getHeader(CorrelationIdFilter.HEADER);
        assertThat(id).isNotEqualTo("bad\nvalue injected");
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void generatesIdWhenAbsent() throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), res, new MockFilterChain());
        assertThat(UUID.fromString(res.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    }
}
