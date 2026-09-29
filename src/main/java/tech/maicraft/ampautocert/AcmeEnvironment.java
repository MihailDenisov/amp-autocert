package tech.maicraft.ampautocert;

public enum AcmeEnvironment {
    STAGING("staging"), PRODUCTION("production");

    private final String directoryName;
    AcmeEnvironment(String directoryName) { this.directoryName = directoryName; }
    public String directoryName() { return directoryName; }
}
