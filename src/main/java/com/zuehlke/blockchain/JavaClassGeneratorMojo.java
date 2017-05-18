package com.zuehlke.blockchain;


import com.zuehlke.blockchain.solidity.CompilerResult;
import com.zuehlke.blockchain.solidity.SolidityCompiler;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.web3j.codegen.SolidityFunctionWrapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

@Mojo(name = "generate-sources",
        defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class JavaClassGeneratorMojo extends AbstractMojo {

    @Parameter(property = "packageName", defaultValue = "com.zuehlke.blockchain.model")
    protected String packageName;

    @Parameter(property = "sourceDestination", defaultValue = "src/main/java")
    protected String sourceDestination;

    @Parameter(property = "soliditySourceFiles", required = true)
    protected FileSet soliditySourceFiles;

    public void execute() throws MojoExecutionException {
        if (soliditySourceFiles.getDirectory() == null) {
            getLog().info("No solidity directory specified, using mavenProject base directory.");
            soliditySourceFiles.setDirectory(new File(Paths.get(".").toUri()).getAbsolutePath());
        }
        if (soliditySourceFiles.getIncludes().size() == 0) {
            getLog().info("No solidity includes specified, using the default (**/*.sol)");
            soliditySourceFiles.setIncludes(Collections.singletonList("**/*.sol"));
        }

        for (String includedFile : new FileSetManager().getIncludedFiles(soliditySourceFiles)) {
            getLog().debug("process '" + includedFile + "'");
            processContractFile(includedFile);
            getLog().debug("processed '" + includedFile + "'");
        }
    }

    private void processContractFile(String includedFile) throws MojoExecutionException {
        String result;
        try {
            getLog().debug("Compile '" + soliditySourceFiles.getDirectory() + File.separator + includedFile + "'");
            result = parseSoliditySource(includedFile);
            getLog().debug("Compiled '" + includedFile + "'");

        } catch (IOException ioException) {
            throw new MojoExecutionException("Could not compile files", ioException);
        }

        Map<String, Map<String, String>> contracts = extractContracts(result);

        for (String contractName : contracts.keySet()) {

            try {
                getLog().debug("Build contract '" + contractName + "'");
                generatedJavaClass(contracts, contractName);
                getLog().debug("java class for contract '" + contractName + "' generated");
            } catch (ClassNotFoundException | IOException ioException) {
                getLog().error("Could not build java class for contract '" + contractName + "'", ioException);
            }
        }
    }

    private Map<String, Map<String, String>> extractContracts(String result) throws MojoExecutionException {
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
            String script = "Java.asJSONCompatible(" + result + ")";
            Map<String, Object> json = (Map<String, Object>) engine.eval(script);
            getLog().debug("Used solC Version: " + json.get("version"));
            return (Map<String, Map<String, String>>) json.get("contracts");
        } catch (ScriptException e) {
            throw new MojoExecutionException("Could not parse SolC result");
        }
    }

    private String parseSoliditySource(String includedFile) throws IOException, MojoExecutionException {
        byte[] contract = Files.readAllBytes(Paths.get(soliditySourceFiles.getDirectory(), includedFile));
        CompilerResult result = SolidityCompiler.getInstance().compileSrc(
                contract,
                true,
                true,
                SolidityCompiler.Options.ABI,
                SolidityCompiler.Options.BIN);
        if (result.isFailed()) {
            throw new MojoExecutionException("Could not compile solidity files\n" + result.errors);
        }
        getLog().debug("SolC Output:\t" + result.output);
        getLog().debug("SolC Error:\t" + result.output);
        return result.output;
    }

    private void generatedJavaClass(Map<String, Map<String, String>> result, String contractName) throws IOException, ClassNotFoundException {
        new SolidityFunctionWrapper().generateJavaFiles(
                contractName,
                result.get(contractName).get("bin"),
                result.get(contractName).get("abi"),
                sourceDestination,
                packageName);
    }
}
