package dev.bytevault.node.web;

import dev.bytevault.node.config.NodeProperties;
import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.RequestSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every {@code /internal/v1/**} request except health. The response never says
 * which check failed; the log records the verdict for operators.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NodeAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NodeAuthFilter.class);

    private final NodeProperties props;
    private final RequestSigner signer;

    public NodeAuthFilter(NodeProperties props) {
        this.props = props;
        this.signer = new RequestSigner(props.authSecret());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(NodeProtocol.PREFIX + "/") || path.equals(NodeProtocol.HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String nodeId = req.getHeader(NodeProtocol.HEADER_NODE_ID);
        RequestSigner.Verdict verdict = signer.verify(req.getMethod(), req.getRequestURI(), nodeId,
                req.getHeader(NodeProtocol.HEADER_TIMESTAMP), req.getHeader(NodeProtocol.HEADER_CONTENT_SHA256),
                req.getHeader(NodeProtocol.HEADER_SIGNATURE));
        if (verdict != RequestSigner.Verdict.VALID) {
            log.warn("Rejected {} {}: {}", req.getMethod(), req.getRequestURI(), verdict);
            NodeErrors.write(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "authentication failed");
            return;
        }
        if (!props.id().equals(nodeId)) {
            log.warn("Rejected {} {}: addressed to node {}", req.getMethod(), req.getRequestURI(), nodeId);
            NodeErrors.write(res, HttpServletResponse.SC_FORBIDDEN, "WRONG_NODE", "request addressed to another node");
            return;
        }
        chain.doFilter(req, res);
    }
}
