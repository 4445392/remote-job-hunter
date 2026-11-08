package jobhunter;

import java.nio.file.Path;

/** Where files live and which optional API keys are set (all via environment variables). */
record Settings(Path home, String braveKey, String serperKey, String anthropicKey, String anthropicModel, String serpapiKey) {

    /** Without a SerpApi key (Google Jobs). */
    Settings(Path home, String braveKey, String serperKey, String anthropicKey, String anthropicModel) {
        this(home, braveKey, serperKey, anthropicKey, anthropicModel, null);
    }

    static Settings fromEnv(Path home) {
        return new Settings(home,
                env("BRAVE_API_KEY"), env("SERPER_API_KEY"), env("ANTHROPIC_API_KEY"),
                env("ANTHROPIC_MODEL") == null ? "claude-sonnet-5-5" : env("ANTHROPIC_MODEL"),
                env("SERPAPI_KEY"));
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? null : v.trim();
    }

    Path profileFile() { return home.resolve("config").resolve("profile.json"); }
    Path stateFile() { return home.resolve("data").resolve("state.json"); }
    Path cvFile(Profile p) { return home.resolve(p.cvFile); }
}
