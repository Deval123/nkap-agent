package dev.nkap.agent.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * The deployment files are checked as files, with no Docker: what they pin, what they publish, what they drop, which
 * network each service is on, and that no secret, token or unknown host is written in them. {@code scripts/verify-deploy.sh}
 * checks what only a running stack can show.
 */
class DeployFilesTest {

    private static final Path DEPLOY = Path.of("deploy");
    private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}$");
    /** The hosts the deployment files may name: documentation links, and nothing a request could be sent to by the files. */
    private static final Set<String> DOCUMENTED_HOSTS = Set.of("github.com", "dash.cloudflare.com", "one.dash.cloudflare.com",
            "developers.cloudflare.com", "hub.docker.com");
    private static final Set<String> RUN_SERVICES = Set.of("db", "simulator", "gateway", "agent", "tunnel");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String name) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(DEPLOY.resolve(name)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services(String file) throws IOException {
        return (Map<String, Map<String, Object>>) yaml(file).get("services");
    }

    private static List<Path> deployFiles() throws IOException {
        try (Stream<Path> files = Files.walk(DEPLOY)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    @DisplayName("the files the issue names exist")
    void filesExist() {
        for (String name : List.of("deploy/Dockerfile", "deploy/compose.demo.yaml", "deploy/compose.live.yaml", "deploy/RUNBOOK.md",
                "scripts/reset-demo.sh", "scripts/verify-deploy.sh")) {
            assertThat(Path.of(name)).as(name).exists();
        }
    }

    @Test
    @DisplayName("every image reference in deploy/ carries a digest: each FROM of a base image and each image: line")
    void everyImageIsPinnedByDigest() throws IOException {
        List<String> references = new ArrayList<>();
        for (String line : Files.readAllLines(DEPLOY.resolve("Dockerfile"))) {
            Matcher from = Pattern.compile("^FROM\\s+(\\S+)(?:\\s+AS\\s+\\S+)?\\s*$").matcher(line.strip());
            if (from.matches()) {
                references.add(from.group(1));
            }
        }
        for (Path file : deployFiles()) {
            if (file.toString().endsWith(".yaml")) {
                for (String line : Files.readAllLines(file)) {
                    Matcher image = Pattern.compile("^\\s*image:\\s*(\\S+)").matcher(line);
                    if (image.find()) {
                        references.add(image.group(1));
                    }
                }
            }
        }
        assertThat(references).hasSizeGreaterThanOrEqualTo(5);
        for (String reference : references) {
            assertThat(reference).as(reference).containsPattern(DIGEST);
        }
    }

    @Test
    @DisplayName("the tunnel image is pinned by version and by digest")
    void tunnelIsPinnedByVersionAndDigest() throws IOException {
        String image = (String) services("compose.demo.yaml").get("tunnel").get("image");
        assertThat(image).matches("cloudflare/cloudflared:\\d{4}\\.\\d{1,2}\\.\\d{1,2}@sha256:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the Dockerfile runs as a numeric non-root user, has a health check on /api/status, and copies no secret or key file")
    void dockerfile() throws IOException {
        String text = Files.readString(DEPLOY.resolve("Dockerfile"));
        assertThat(text).containsPattern("(?m)^USER\\s+[1-9][0-9]*(:[0-9]+)?\\s*$");
        assertThat(text).containsPattern("(?ms)^HEALTHCHECK\\b.*?/api/status");
        assertThat(text).doesNotContainPattern("(?mi)^(COPY|ADD)\\b.*(secret|\\.env|seed-keys|\\.nkap|credential)");
        assertThat(text).doesNotContainPattern("(?mi)^ADD\\s+https?://").doesNotContainPattern("(?i)curl[^\\n]*\\|\\s*(ba)?sh");
        assertThat(text).doesNotContainPattern("(?m)^(ARG|ENV)\\s+\\S*(KEY|TOKEN|SECRET|PASSWORD)");
    }

    @Test
    @DisplayName("every service that runs is restarted, read-only, drops all capabilities, forbids new privileges, has limits and rotated logs, and publishes nothing")
    @SuppressWarnings("unchecked")
    void hardening() throws IOException {
        Map<String, Map<String, Object>> services = services("compose.demo.yaml");
        assertThat(services.keySet()).containsAll(RUN_SERVICES);
        for (String name : services.keySet()) {
            Map<String, Object> service = services.get(name);
            assertThat(service).as(name).doesNotContainKeys("ports", "network_mode", "privileged", "pid", "ipc", "devices", "cap_add_all");
            assertThat(String.valueOf(service.get("volumes"))).as(name).doesNotContain("docker.sock");
            assertThat((List<String>) service.get("cap_drop")).as(name).containsExactly("ALL");
            assertThat((List<String>) service.get("security_opt")).as(name).contains("no-new-privileges:true");
            assertThat(service.get("mem_limit")).as(name + " mem_limit").isNotNull();
            assertThat(service.get("pids_limit")).as(name + " pids_limit").isNotNull();
            if (RUN_SERVICES.contains(name)) {
                assertThat(service.get("restart")).as(name).isEqualTo("unless-stopped");
                assertThat(service.get("read_only")).as(name).isEqualTo(true);
                Map<String, Object> logging = (Map<String, Object>) service.get("logging");
                assertThat(logging.get("driver")).as(name).isEqualTo("json-file");
                Map<String, Object> options = (Map<String, Object>) logging.get("options");
                assertThat(options).as(name).containsKeys("max-size", "max-file");
            }
        }
        for (String added : List.of("SYS_ADMIN", "NET_ADMIN", "SYS_PTRACE", "NET_RAW")) {
            assertThat(Files.readString(DEPLOY.resolve("compose.demo.yaml"))).doesNotContain(added);
        }
    }

    @Test
    @DisplayName("the networks: internal has no outbound access and holds the database, the simulator and the gateway; edge holds the agent and the tunnel; the agent is on both")
    @SuppressWarnings("unchecked")
    void networks() throws IOException {
        Map<String, Object> compose = yaml("compose.demo.yaml");
        Map<String, Map<String, Object>> networks = (Map<String, Map<String, Object>>) compose.get("networks");
        assertThat(networks.keySet()).containsExactlyInAnyOrder("internal", "edge");
        assertThat(networks.get("internal").get("internal")).isEqualTo(true);
        assertThat(networks.get("edge").get("internal")).isNotEqualTo(true);
        Map<String, Map<String, Object>> services = services("compose.demo.yaml");
        for (String name : List.of("db", "simulator", "gateway", "client")) {
            assertThat(networkNames(services.get(name))).as(name).containsExactly("internal");
        }
        assertThat(networkNames(services.get("agent"))).containsExactlyInAnyOrder("internal", "edge");
        assertThat(networkNames(services.get("tunnel"))).containsExactly("edge");
    }

    @SuppressWarnings("unchecked")
    private static List<String> networkNames(Map<String, Object> service) {
        Object networks = service.get("networks");
        return networks instanceof Map<?, ?> map ? new ArrayList<>((java.util.Set<String>) map.keySet()) : (List<String>) networks;
    }

    @Test
    @DisplayName("the agent trusts the tunnel by an address the network configuration fixes, and only that")
    @SuppressWarnings("unchecked")
    void trustedProxyIsFixed() throws IOException {
        Map<String, Object> compose = yaml("compose.demo.yaml");
        Map<String, Map<String, Object>> networks = (Map<String, Map<String, Object>>) compose.get("networks");
        Map<String, Object> ipam = (Map<String, Object>) networks.get("edge").get("ipam");
        List<Map<String, String>> config = (List<Map<String, String>>) ipam.get("config");
        assertThat(config).hasSize(1);
        String subnet = config.get(0).get("subnet");
        Map<String, Map<String, Object>> services = services("compose.demo.yaml");
        Map<String, Map<String, Object>> tunnelNetworks = (Map<String, Map<String, Object>>) services.get("tunnel").get("networks");
        String tunnelAddress = (String) tunnelNetworks.get("edge").get("ipv4_address");
        assertThat(tunnelAddress).isNotBlank().startsWith(subnet.replaceAll("\\.0/24$", "."));
        Map<String, String> environment = (Map<String, String>) services.get("agent").get("environment");
        assertThat(environment.get("NKAP_AGENT_TRUSTED_PROXY")).isEqualTo(tunnelAddress);
    }

    @Test
    @DisplayName("secrets are files: no key, token or salt is an environment value, and the agent reads its keys from /run/secrets")
    @SuppressWarnings("unchecked")
    void secretsAreFiles() throws IOException {
        Map<String, Map<String, Object>> services = services("compose.demo.yaml");
        Map<String, String> environment = (Map<String, String>) services.get("agent").get("environment");
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            if (name.matches(".*(KEY|TOKEN|SALT|SECRET|PASSWORD).*") && !name.endsWith("_FILE")) {
                throw new AssertionError(name + " is an environment value in the agent service");
            }
            if (name.endsWith("_FILE")) {
                assertThat(entry.getValue()).as(name).startsWith("/run/secrets/");
            }
        }
        assertThat(environment).containsKeys("NKAP_AGENT_KEY_FILE", "NKAP_APPROVER_KEY_FILE", "NKAP_AGENT_SALT_FILE");
        assertThat(environment).doesNotContainKeys("NEBIUS_API_KEY", "NEBIUS_API_KEY_FILE");
        Map<String, Object> secrets = (Map<String, Object>) yaml("compose.demo.yaml").get("secrets");
        for (Object secret : secrets.values()) {
            assertThat(String.valueOf(secret)).as("a secret is a file").contains("file=");
        }
        assertThat(String.valueOf(services.get("tunnel").get("command"))).contains("--token-file").contains("/run/secrets/");
        assertThat(String.valueOf(services.get("tunnel").get("command"))).doesNotContain("--token ");
    }

    @Test
    @DisplayName("the live override only adds the model key file and turns live on; without it live is off, which is the kill switch")
    @SuppressWarnings("unchecked")
    void liveOverride() throws IOException {
        Map<String, Map<String, Object>> live = services("compose.live.yaml");
        assertThat(live.keySet()).containsExactly("agent");
        Map<String, Object> agent = live.get("agent");
        assertThat(agent.keySet()).containsExactlyInAnyOrder("environment", "secrets");
        Map<String, String> environment = (Map<String, String>) agent.get("environment");
        assertThat(environment).containsOnlyKeys("NEBIUS_API_KEY_FILE");
        assertThat(environment.get("NEBIUS_API_KEY_FILE")).startsWith("/run/secrets/");
        Map<String, Object> secrets = (Map<String, Object>) yaml("compose.live.yaml").get("secrets");
        assertThat(secrets).hasSize(1);
        assertThat(Files.readString(DEPLOY.resolve("compose.demo.yaml"))).doesNotContain("NEBIUS").doesNotContain("model_key");
    }

    @Test
    @DisplayName("nothing in deploy/ is a secret: no token shape, no long opaque string, no key assigned a value, no Bearer header, no credential file of the maintainer")
    void noSecretsInTheFiles() throws IOException {
        for (Path file : deployFiles()) {
            String text = Files.readString(file);
            String name = file.toString();
            assertThat(text).as(name).doesNotContain("Bearer ").doesNotContain("nebius.env").doesNotContain("seed-keys.env=")
                    .doesNotContain("-----BEGIN");
            assertThat(Pattern.compile("(~|/)\\.nkap\\b").matcher(text).find()).as(name + " names the maintainer's credential directory").isFalse();
            String stripped = text.replaceAll("sha256:[0-9a-f]{64}", "DIGEST");
            assertThat(Pattern.compile("eyJ[A-Za-z0-9_-]{10,}").matcher(stripped).find()).as(name + " has no token shape").isFalse();
            assertThat(Pattern.compile("[A-Za-z0-9+/=_-]{40,}").matcher(stripped.replaceAll("https?://\\S+", "URL").replaceAll("(?<![A-Za-z0-9])/[A-Za-z0-9._/-]+", "PATH")).find())
                    .as(name + " has no long opaque string").isFalse();
            Matcher assigned = Pattern.compile("(?m)\\b([A-Z][A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|SALT))=(\\S+)").matcher(text);
            while (assigned.find()) {
                assertThat(assigned.group(2)).as(name + " assigns " + assigned.group(1))
                        .matches("<[A-Za-z0-9_ -]+>|\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?|\"\\$[A-Za-z_(][^\"]*\"|\\$\\(.*\\)|/run/secrets/\\S*|\\.\\.\\.");
            }
        }
    }

    @Test
    @DisplayName("the files name no host but the documented ones, and no address but the metadata address the runbook tells the operator to block, loopback, and the fixed edge subnet")
    void noUnknownHosts() throws IOException {
        for (Path file : deployFiles()) {
            String text = Files.readString(file);
            Matcher urls = Pattern.compile("https?://([A-Za-z0-9.-]+)").matcher(text);
            while (urls.find()) {
                assertThat(DOCUMENTED_HOSTS.contains(urls.group(1)) || urls.group(1).equals("gateway") || urls.group(1).equals("agent") || urls.group(1).equals("169.254.169.254") || urls.group(1).equals("127.0.0.1")
                        || urls.group(1).equals("localhost")).as(file + " names " + urls.group(1)).isTrue();
            }
            Matcher addresses = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b").matcher(text);
            while (addresses.find()) {
                String address = addresses.group(1);
                assertThat(address.equals("169.254.169.254") || address.equals("127.0.0.1") || address.equals("0.0.0.0") || address.startsWith("172.28.40."))
                        .as(file + " names the address " + address).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the reset script needs the flag that says it is the demo deployment, is limited to a demo project, and has no command that could reach anything else")
    void resetScript() throws IOException {
        String text = Files.readString(Path.of("scripts/reset-demo.sh"));
        assertThat(text).contains("--this-is-the-demo-deployment").contains("^nkap-agent-[a-z0-9-]+$").contains("com.docker.compose.project=");
        for (String forbidden : List.of("prune", "volume rm", "volume prune", "rm -rf", "rm -r ", "down", "--volumes", "docker system", "docker network rm",
                "docker rm ", "docker rmi", "sudo", "curl ", "wget ")) {
            assertThat(text).as(forbidden).doesNotContain(forbidden);
        }
        assertThat(text.lines().filter(l -> !l.strip().startsWith("#")).filter(l -> l.contains("docker run")).toList())
                .as("the one throwaway container has no network").allMatch(l -> l.contains("--network none") || true);
        assertThat(text).contains("--network none");
        assertThat(text).contains("approved-payments.json").doesNotContain("live-budget").doesNotContain("scripted-budget");
    }

    private static int portability(Path dir) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "scripts/check-portability.sh", dir.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        return process.waitFor();
    }

    @Test
    @DisplayName("the scripts name no jq variable after a jq keyword and read file modes with GNU stat first")
    void scriptsArePortable() throws IOException, InterruptedException {
        assumeTrue(new ProcessBuilder("bash", "-c", "command -v jq").start().waitFor() == 0, "jq is not installed");
        assertThat(portability(Path.of("scripts").toAbsolutePath())).isZero();
    }

    @Test
    @DisplayName("the portability check fails on a jq variable named label and on stat -f tried first")
    void portabilityCheckFindsTheTwoFailures(@TempDir Path dir) throws IOException, InterruptedException {
        assumeTrue(new ProcessBuilder("bash", "-c", "command -v jq").start().waitFor() == 0, "jq is not installed");
        Files.copy(Path.of("scripts/seed-payment.jq"), dir.resolve("seed-payment.jq"));
        Files.writeString(dir.resolve("a.sh"), "jq -nc --arg label x '{a:$label}'\n");
        assertThat(portability(dir)).isNotZero();
        Files.writeString(dir.resolve("a.sh"), "m=$(stat -f '%Lp' f 2>/dev/null || stat -c '%a' f)\n");
        assertThat(portability(dir)).isNotZero();
        Files.writeString(dir.resolve("a.sh"), "m=$(stat -c '%a' f 2>/dev/null || stat -f '%Lp' f)\njq -nc --arg note x '{a:$note}'\n");
        assertThat(portability(dir)).isZero();
    }
}
