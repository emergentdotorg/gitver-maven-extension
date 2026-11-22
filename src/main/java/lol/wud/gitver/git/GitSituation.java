package lol.wud.gitver.git;

import java.io.File;

public interface GitSituation {
    String getRefName();
    String getRev();
    boolean isClean();

    GitDescription getDescription();

    File getRootDirectory();
}
