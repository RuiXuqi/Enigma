WIP: This subproject implements an MCP Server, allowing AI agents to work with mapping

---

## Shared HTTP server

The default stdio transport starts one Enigma JVM for every MCP client. Clients that fan out work to multiple agents should instead start one shared Streamable HTTP server:

```shell
java -jar enigma-mcp-<version>-all.jar \
  --jar <input.jar> \
  --mapping <mapping-path> \
  --mapping-format <format> \
  --http-port 3765
```

The server listens only on `127.0.0.1` and exposes MCP at `http://127.0.0.1:3765/mcp`. Start it once, then configure Codex to connect to the shared endpoint:

```toml
[mcp_servers.enigma-mcp]
url = "http://127.0.0.1:3765/mcp"
```

Each agent gets an independent MCP session while all sessions share the same indexed jar and `EnigmaProject`.

---

Common human workflow, MCP should probably provide same or similar tools:
- open jar (not required, handled by enigma-server)
- open mapping (not required, handled by enigma-server)
- get currently obfuscated class/method/field/param
- search class/method/field
- go to definition
- do changes to the mapping
- save mapping to a certain path
