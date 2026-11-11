package com.sage.business.code.application.personalaccess;
/** Test-only loopback owner helper around the real production PAC adapter and actual native source.
 * Tokens are inherited, never passed as arguments or written to ready files. */
public final class MacGraphicsPacFixtureMain {
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("MAC001_GRAPHICS")))throw new IllegalArgumentException("EXPLICIT_GRAPHICS_HARNESS_REQUIRED");
        MacPacFixtureMain.main(args);
    }
}
