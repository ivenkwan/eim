package io.eim.coreapi;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class CoreApiApplication {

    public static void main(String[] args) {
        Quarkus.run(args);
    }
}
