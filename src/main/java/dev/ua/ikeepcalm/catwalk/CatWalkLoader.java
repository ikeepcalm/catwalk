package dev.ua.ikeepcalm.catwalk;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class CatWalkLoader implements PluginLoader {

    private static final List<String> LIBRARIES = List.of(
            "io.javalin:javalin:7.2.3",
            "io.javalin.community.ssl:javalin-ssl:7.2.3",
            "io.javalin.community.openapi:openapi-specification:7.2.3",
            "com.fasterxml.jackson.core:jackson-databind:2.22.4",
            "com.zaxxer:HikariCP:7.1.0",
            "org.mariadb.jdbc:mariadb-java-client:3.5.11",
            "org.slf4j:slf4j-jdk14:2.0.17"
    );

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR
        ).build());
        LIBRARIES.forEach(library -> resolver.addDependency(new Dependency(new DefaultArtifact(library), null)));
        classpathBuilder.addLibrary(resolver);
    }
}
