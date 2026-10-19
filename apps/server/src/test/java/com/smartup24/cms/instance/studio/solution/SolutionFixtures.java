package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.service.StudioPackages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The shipped order template as a tree to change and seal again, for the tests of ADR-0047. */
final class SolutionFixtures {
    static final Path ORDER = Path.of("src/main/resources/studio/templates/order-approval.json");

    final ObjectMapper json = JsonMapper.shared();
    final MdSimpleFormDeclarations declarations = new MdSimpleFormDeclarations(json);
    final StudioPackages forms = new StudioPackages(json, declarations);
    final SolutionFormat format = new SolutionFormat(json, forms);

    ObjectNode order() {
        try {
            return (ObjectNode) json.readTree(Files.readString(ORDER, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The order template changed by {@code change}, with its forms prepared and its checksum written again. */
    byte[] changed(Consumer<ObjectNode> change) {
        ObjectNode root = order();
        change.accept(root);
        return sealed(root);
    }

    byte[] sealed(ObjectNode root) {
        for (JsonNode form : root.path("forms")) {
            if (form.get("declaration") == null) continue;
            String checksum = declarations
                    .prepare(json.writeValueAsBytes(form.get("declaration")))
                    .checksum();
            ((ObjectNode) form).put("checksum", checksum);
        }
        root.put("checksum", format.checksum(root));
        return json.writeValueAsBytes(root);
    }

    SolutionPackage read(byte[] bytes) {
        return format.read(bytes);
    }
}
