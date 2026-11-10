package com.jitsimeet.api.lifecycle;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

final class LifecycleProtocol {
    static final String ROUTE = "/internal/lifecycle/v1/events";
    static final int MAX_BODY = 16384;
    private final LifecycleProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    record Observation(String source, String epoch, String eventId, String instance, int sequence,
                       String type, String identity, String payload) {}
    static final class Rejected extends RuntimeException {
        final int status;
        Rejected(int status) { super("lifecycle request rejected"); this.status = status; }
    }
    LifecycleProtocol(LifecycleProperties properties, Clock clock) {
        this.properties = properties; this.clock = clock;
        if (properties.keys() == null || properties.keys().isEmpty()
                || properties.mainDomains() == null || properties.mainDomains().isEmpty()
                || properties.breakoutDomains() == null) throw new IllegalArgumentException("lifecycle configuration required");
        properties.keys().forEach((id, key) -> {
            if (!token(id) || key == null || !token(key.sourceId()) || key.secretBase64() == null
                    || Base64.getDecoder().decode(key.secretBase64()).length < 32)
                throw new IllegalArgumentException("invalid lifecycle credentials");
        });
        var domains = new HashSet<String>();
        for (var list : List.of(properties.mainDomains(), properties.breakoutDomains())) for (String domain : list) {
            if (domain == null || !domain.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*") || !domains.add(domain))
                throw new IllegalArgumentException("invalid lifecycle domain scope");
        }
    }
    private static boolean token(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    private static void require(boolean valid) { if (!valid) throw new Rejected(400); }
    private static String text(JsonNode node, String key) {
        require(node.has(key) && node.get(key).isTextual()); return node.get(key).textValue();
    }
    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        require(node != null && node.isObject());
        required.forEach(key -> require(node.has(key)));
        node.fieldNames().forEachRemaining(key -> require(required.contains(key) || optional.contains(key)));
    }
    private static String uuid(JsonNode node, String key) {
        String value = text(node, key);
        require(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")); return value;
    }
    Observation decode(byte[] body, String keyId, String timestamp, String signature) {
        var key = keyId == null ? null : properties.keys().get(keyId);
        try {
            if (key == null || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                    || signature == null || !signature.matches("[0-9a-f]{64}")
                    || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > 300) throw new Rejected(401);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String input = "jitsi-lifecycle-v1\n" + keyId + "\n" + timestamp + "\nPOST\n" + ROUTE + "\n" + hash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(key.secretBase64()), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(signature)))
                throw new Rejected(401);
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JsonNode root;
            try (var parser = json.createParser(utf8)) {
                root = json.readTree(parser); require(parser.nextToken() == null);
            }
            fields(root, Set.of("schemaVersion", "sourceId", "producerEpoch", "event"), Set.of());
            require(root.get("schemaVersion").isInt() && root.get("schemaVersion").intValue() == 1);
            String source = text(root, "sourceId");
            if (!key.sourceId().equals(source)) throw new Rejected(401);
            String epoch = uuid(root, "producerEpoch"); JsonNode e = root.get("event");
            fields(e, Set.of("eventId", "instanceId", "sequence", "type", "roomJid", "kind",
                    "creationObserved", "binding", "parentBinding"), Set.of("parentInstanceId"));
            String eventId = uuid(e, "eventId"), instance = uuid(e, "instanceId");
            String type = text(e, "type"), kind = text(e, "kind"), room = text(e, "roomJid");
            require(Set.of("main", "breakout").contains(kind) && text(e, "binding").equals("unknown"));
            require(e.get("creationObserved").isBoolean() && e.get("sequence").isInt());
            boolean observed = e.get("creationObserved").booleanValue(); int sequence = e.get("sequence").intValue();
            require((type.equals("created") && observed && sequence == 1)
                    || (type.equals("destroyed") && sequence == (observed ? 2 : 1)));
            require(room.length() <= 512 && room.matches("[^@/\\s\\p{Cntrl}]+@[a-z0-9.-]+"));
            String domain = room.substring(room.indexOf('@') + 1);
            require((kind.equals("main") ? properties.mainDomains() : properties.breakoutDomains()).contains(domain));
            String parent = null;
            if (e.has("parentInstanceId")) {
                parent = uuid(e, "parentInstanceId");
                require(kind.equals("breakout") && !parent.equals(instance) && text(e, "parentBinding").equals("observed"));
            } else require(text(e, "parentBinding").equals("unknown"));
            // Fixed-order scalar identity/payload: JSON whitespace and object order are not event identity.
            String identity = json.writeValueAsString(Arrays.asList(room, kind, observed, parent, text(e, "parentBinding")));
            String payload = json.writeValueAsString(List.of(eventId, instance, sequence, type, identity));
            return new Observation(source, epoch, eventId, instance, sequence, type, identity, payload);
        } catch (Rejected rejected) { throw rejected;
        } catch (Exception invalid) { throw new Rejected(400); }
    }
}
