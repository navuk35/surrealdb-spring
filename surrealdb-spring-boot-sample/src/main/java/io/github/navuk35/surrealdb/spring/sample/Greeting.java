package io.github.navuk35.surrealdb.spring.sample;

public class Greeting {

    private String name;
    private String message;

    public Greeting() {
    }

    public Greeting(String name, String message) {
        this.name = name;
        this.message = message;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
