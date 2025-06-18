package dev.ua.uaproject.catwalk;

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

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        // Create Maven library resolver
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        // Add Maven Central repository
        resolver.addRepository(new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build());

        // Define all our dependencies
        List<Dependency> dependencies = List.of(
                // Core Javalin web framework
                new Dependency(new DefaultArtifact("io.javalin:javalin-bundle:6.6.0"), null),

                // Javalin OpenAPI plugins
                new Dependency(new DefaultArtifact("io.javalin.community.openapi:javalin-openapi-plugin:6.6.0"), null),
                new Dependency(new DefaultArtifact("io.javalin.community.openapi:javalin-swagger-plugin:6.6.0"), null),
                new Dependency(new DefaultArtifact("io.javalin.community.openapi:javalin-redoc-plugin:6.6.0"), null),
                new Dependency(new DefaultArtifact("io.javalin.community.ssl:ssl-plugin:6.6.0"), null),

                // Jackson JSON processing
                new Dependency(new DefaultArtifact("com.fasterxml.jackson.core:jackson-core:2.16.1"), null),
                new Dependency(new DefaultArtifact("com.fasterxml.jackson.core:jackson-databind:2.16.1"), null),
                new Dependency(new DefaultArtifact("com.fasterxml.jackson.core:jackson-annotations:2.16.1"), null),

                // Apache Commons utilities
                new Dependency(new DefaultArtifact("org.apache.commons:commons-compress:1.24.0"), null),
                new Dependency(new DefaultArtifact("commons-io:commons-io:2.13.0"), null),
                new Dependency(new DefaultArtifact("org.apache.commons:commons-lang3:3.12.0"), null)
        );

        // Add all dependencies to the resolver
        dependencies.forEach(resolver::addDependency);

        // Add the resolver to the classpath builder
        classpathBuilder.addLibrary(resolver);
    }
}