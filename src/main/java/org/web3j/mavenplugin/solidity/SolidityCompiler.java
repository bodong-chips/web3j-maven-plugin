package org.web3j.mavenplugin.solidity;

import org.apache.maven.plugin.logging.Log;
import org.web3j.sokt.SolcInstance;
import org.web3j.sokt.SolidityFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles the given Solidity Contracts into binary code.
 * <p>
 * Inspired by https://github.com/ethereum/ethereumj/tree/develop/ethereumj-core/src/main/java/org/ethereum/solidity
 */
public class SolidityCompiler {

    private static SolidityCompiler INSTANCE;
    private Log LOG;
    private String usedSolCVersion;

    private SolidityCompiler(Log log) {
        this.LOG = log;
    }

    public static SolidityCompiler getInstance(Log log) {
        if (INSTANCE == null) {
            INSTANCE = new SolidityCompiler(log);
        }
        return INSTANCE;
    }

    public CompilerResult compileSrc(
            String rootDirectory, Collection<String> sources,
            String[] pathPrefixes,
            SolidityCompiler.Options... options) {


        boolean success = false;
        String error;
        String output;

        try {
            Process process = getSolcProcessFromSokt(rootDirectory, sources, pathPrefixes, options);

            ParallelReader errorReader = new ParallelReader(process.getErrorStream());
            ParallelReader outputReader = new ParallelReader(process.getInputStream());
            errorReader.start();
            outputReader.start();

            success = process.waitFor() == 0;
            error = errorReader.getContent();
            output = outputReader.getContent();

        } catch (IOException | InterruptedException e) {
            StringWriter errorWriter = new StringWriter();
            e.printStackTrace(new PrintWriter(errorWriter));
            error = errorWriter.toString();
            output = "";
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        return new CompilerResult(error, output, success);
    }

    private Process getSolcProcessFromSokt(String rootDirectory, Collection<String> sources, String[] pathPrefixes, Options[] options) throws IOException {
        SolidityFile solidityFile = new SolidityFile(Paths.get(rootDirectory, sources.iterator().next()).toFile().getAbsolutePath());
        SolcInstance instance = solidityFile.getCompilerInstance(".web3j", true);
        if (!instance.installed()) instance.install();
        usedSolCVersion = instance.getSolcRelease().getVersion();
        Process process;
        List<String> commandParts = prepareCommandOptions(instance.getSolcFile().getAbsolutePath(), rootDirectory, sources, pathPrefixes, options);
        process = Runtime.getRuntime().exec(commandParts.toArray(new String[commandParts.size()]));
        return process;
    }

    private Map<String, String> getAbsolutePathPrefixes(String rootDirectory, String[] pathPrefixes) {
        return Stream.of(pathPrefixes)
                .map(pathPrefix -> replaceMakePathPrefixAbsolute(rootDirectory, pathPrefix))
                .collect(Collectors.toMap(p -> p[0], p -> p[1]));
    }

    private String prepareAllowPath(String rootDirectory, String[] pathPrefixes) {
        return Stream.concat(
                Stream.of(rootDirectory).map(this::toAbsolutePath),
                getAbsolutePathPrefixes(rootDirectory, pathPrefixes).values().stream()
        ).collect(Collectors.joining(","));
    }

    private List<String> prepareCommandOptions(String canonicalSolCPath, String rootDirectory, Collection<String> sources, String[] pathPrefixes, SolidityCompiler.Options... options) {
        String outputFormats = Arrays.stream(options).map(Options::toString).collect(Collectors.joining(","));
        String allowedPaths = prepareAllowPath(rootDirectory, pathPrefixes);
        List<String> dependencyPath = getAbsolutePathPrefixes(rootDirectory, pathPrefixes).entrySet().stream().map(entry1 -> entry1.getKey() + "=" + entry1.getValue()).collect(Collectors.toList());
        List<String> sourceFiles = sources.stream().map(source -> toAbsolutePath(rootDirectory, source)).toList();

        List<String> commandParts = new ArrayList<>();
        commandParts.add(canonicalSolCPath);
        commandParts.add("--optimize");
        commandParts.add("--combined-json");
        commandParts.add(outputFormats);
        commandParts.add("--allow-paths");
        commandParts.add(allowedPaths);
        commandParts.addAll(dependencyPath);
        commandParts.addAll(sourceFiles);
        return commandParts;
    }

    private String toAbsolutePath(String baseDirectory, String... subDirectories) {
        return Paths.get(baseDirectory, subDirectories).normalize().toFile().getAbsolutePath();
    }

    String[] replaceMakePathPrefixAbsolute(String baseDirectory, String pathPrefix) {
        String[] prefixAndPath = pathPrefix.split("=", 2);
        prefixAndPath[1] = toAbsolutePath(baseDirectory, prefixAndPath[1]);
        return prefixAndPath;
    }

    public String getUsedSolCVersion() {
        return usedSolCVersion;
    }

    public enum Options {
        BIN("bin"),
        BIN_RUNITME("bin-runtime"),
        ABI("abi"),
        METADATA("metadata");

        private final String name;

        Options(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class ParallelReader extends Thread {

        private InputStream stream;
        private String content;

        ParallelReader(InputStream stream) {
            this.stream = stream;
        }

        public String getContent() {
            return getContent(true);
        }

        public synchronized String getContent(boolean waitForComplete) {
            if (waitForComplete) {
                while (stream != null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // we are being interrupted so we should stop running
                        return null;
                    }
                }
            }
            return content;
        }

        @Override
        public void run() {

            try (BufferedReader buffer = new BufferedReader(new InputStreamReader(stream))) {
                content = buffer.lines().collect(Collectors.joining(System.lineSeparator()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                synchronized (this) {
                    stream = null;
                    notifyAll();
                }
            }
        }
    }
}
