package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.knowledge.ingest.Chunker;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.config.SsoProps;
import cc.ginpika.minis.ai.knowledge.dto.DocumentDtos;
import cc.ginpika.minis.ai.knowledge.dto.GraphDtos;
import cc.ginpika.minis.ai.knowledge.dto.IngestDtos;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoService;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 管理端：灌入 seed + 图谱维护。门禁：SSO 关闭时（dev）放行；否则需 role=admin 或命中 admin-usernames。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ChunkStore chunks;
    private final GraphStore graph;
    private final DocumentStore documents;
    private final Chunker chunker;
    private final EmbeddingClient embedding;
    private final SsoProps ssoProps;
    private final SsoService ssoService;

    public AdminController(ChunkStore chunks, GraphStore graph, DocumentStore documents,
                           Chunker chunker, EmbeddingClient embedding, SsoProps ssoProps,
                           SsoService ssoService) {
        this.chunks = chunks;
        this.graph = graph;
        this.documents = documents;
        this.chunker = chunker;
        this.embedding = embedding;
        this.ssoProps = ssoProps;
        this.ssoService = ssoService;
    }

    @PostMapping("/ingest")
    public IngestDtos.IngestResult ingest(HttpServletRequest request,
                                         @RequestBody IngestDtos.IngestRequest req) {
        requireAdmin(request);
        if (req.content() == null || req.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 为空");
        }
        String source = first(req.source(), "note");
        String ref = first(req.sourceRef(), source + ":" + first(req.title(), System.currentTimeMillis() + ""));
        List<String> tags = req.tags() == null ? List.of() : req.tags();

        List<String> pieces = chunker.split(req.content());
        boolean embedded = false;
        for (int i = 0; i < pieces.size(); i++) {
            long id = chunks.replace(ref + "#" + i, source, ref,
                    pieces.size() == 1 ? first(req.title(), ref) : (first(req.title(), ref) + " " + (i + 1)),
                    pieces.get(i), tags);
            if (req.mentions() != null) {
                for (String name : req.mentions()) {
                    ensureAndLink(name, id);
                }
            }
            Optional<float[]> vec = embedding.embed(pieces.get(i));
            if (vec.isPresent()) {
                chunks.saveEmbedding(id, embedding.model(), vec.get());
                embedded = true;
            }
        }
        return new IngestDtos.IngestResult(pieces.size(), embedded);
    }

    @PutMapping("/entities")
    public GraphDtos.EntityResult putEntity(HttpServletRequest request,
                                            @RequestBody GraphDtos.EntityRequest req) {
        requireAdmin(request);
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 为空");
        }
        String type = first(req.type(), "CONCEPT");
        graph.upsertEntity(type, name, req.aliases() == null ? List.of() : req.aliases(), req.summary());
        GraphStore.Entity e = graph.byName(name);
        return new GraphDtos.EntityResult(e.id(), e.type(), e.name());
    }

    @PostMapping("/relations")
    public GraphDtos.RelationResult putRelation(HttpServletRequest request,
                                                @RequestBody GraphDtos.RelationRequest req) {
        requireAdmin(request);
        GraphStore.Entity subj = resolve(req.subject());
        GraphStore.Entity obj = resolve(req.object());
        String pred = first(req.predicate(), "relates_to");
        graph.upsertRelation(subj.id(), pred, obj.id(), null);
        return new GraphDtos.RelationResult(
                GraphStore.entityIdFor(subj.name() + pred + obj.name()), subj.name(), pred, obj.name());
    }

    /** 发布/更新文章：markdown 存 data/article/{url}.md + documents 索引行（更新保留首发布时间） */
    @PostMapping("/articles")
    public DocumentDtos.PublishResult publishArticle(HttpServletRequest request,
                                                         @RequestBody DocumentDtos.PublishRequest req) {
        requireAdmin(request);
        String url = req.url() == null ? "" : req.url().trim().toLowerCase();
        if (!SLUG.matcher(url).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "url 需为小写字母/数字/短横线组成的 slug（1~80 位）");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 为空");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 为空");
        }
        documents.saveArticle(url, req.title().trim(), req.summary(), req.content());
        return new DocumentDtos.PublishResult(url);
    }

    // ---------- 私有 ----------

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,79}$");

    private GraphStore.Entity resolve(String name) {
        String n = name == null ? "" : name.trim();
        GraphStore.Entity e = graph.byName(n);
        if (e == null) {
            graph.upsertEntity("CONCEPT", n, List.of(), null);
            e = graph.byName(n);
        }
        return e;
    }

    private void ensureAndLink(String rawName, long chunkId) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return;
        }
        GraphStore.Entity e = graph.byName(name);
        if (e == null) {
            graph.upsertEntity("CONCEPT", name, List.of(), null);
            e = graph.byName(name);
        }
        graph.linkEntityChunk(chunkId, e.id());
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!ssoService.isEnabled()) {
            return; // dev 直通
        }
        SsoUser user = (SsoUser) request.getAttribute(SsoInterceptor.ATTR);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        boolean allowed = "admin".equalsIgnoreCase(user.getRole())
                || ssoProps.getAdminUsernames().contains(user.getUsername());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
    }

    private static String first(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }
}
