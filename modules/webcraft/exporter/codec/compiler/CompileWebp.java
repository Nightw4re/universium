import com.dylibso.chicory.build.time.compiler.Config;
import com.dylibso.chicory.build.time.compiler.Generator;

import java.io.IOException;
import java.nio.file.Path;

public final class CompileWebp {
    private CompileWebp() {}

    public static void main(String[] args) throws IOException {
        var resources = Path.of(args[2]);
        var config = Config.builder()
                .withWasmFile(Path.of(args[0]))
                .withName("com.universium.webcraft.exporter.codec.WebpModule")
                .withTargetSourceFolder(Path.of(args[1]))
                .withTargetClassFolder(resources)
                .withTargetWasmFolder(resources)
                .build();
        var compiler = new Generator(config);
        compiler.generateMetaWasm(compiler.generateResources());
        compiler.generateSources();
    }
}
