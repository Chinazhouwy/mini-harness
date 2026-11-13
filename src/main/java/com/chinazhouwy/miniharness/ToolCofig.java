package com.chinazhouwy.miniharness;

import org.springaicommunity.agent.tools.GlobTool;

public class ToolCofig {


    public static GlobTool getGlobTool(String path){
        return GlobTool.builder()
                .workingDirectory(path)
                .allowedDirectory(path)
                .maxDepth(3)
                .maxResults(10)
                .build();
    }

}
