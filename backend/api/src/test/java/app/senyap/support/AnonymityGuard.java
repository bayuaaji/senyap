package app.senyap.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Penjaga anonimitas (build guide §4 Fase 0, §5 poin 8): memindai body JSON dan melaporkan
 * (1) field yang namanya ID user internal ({@code user_id}, {@code author_id}, {@code sender_id}, …,
 * snake_case maupun camelCase), dan (2) nilai apa pun yang sama dengan salah satu user ID yang ada di DB.
 */
public final class AnonymityGuard {

    /** Dinormalisasi: huruf kecil tanpa underscore. */
    static final Set<String> FORBIDDEN_KEYS = Set.of(
            "userid", "authorid", "senderid", "ownerid", "initiatorid", "recipientid", "reporterid",
            "blockerid", "blockedid", "phone", "phonenumber", "phonehmac");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AnonymityGuard() {
    }

    public static List<String> scan(String json, Set<String> userIds) {
        var violations = new ArrayList<String>();
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            return violations; // bukan JSON valid; bukan urusan penjaga ini
        }
        walk(root, "$", userIds, violations);
        return violations;
    }

    private static void walk(JsonNode node, String path, Set<String> userIds, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String normalized = e.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                String child = path + "." + e.getKey();
                if (FORBIDDEN_KEYS.contains(normalized)) {
                    out.add("field terlarang " + child);
                }
                walk(e.getValue(), child, userIds, out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", userIds, out);
            }
        } else if (node.isValueNode() && !node.isNull()) {
            String v = node.isNumber() ? node.numberValue().toString() : node.asString();
            if (userIds.contains(v)) {
                out.add("nilai user ID di " + path);
            }
        }
    }
}
