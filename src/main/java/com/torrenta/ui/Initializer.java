package com.torrenta.ui;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that finishes building an object its constructor leaves half-made, and that the
 * constructor (or its one caller) always runs before anything else touches the object. NullAway
 * treats any annotation named {@code Initializer} this way: a field the method always assigns
 * counts as non-null, as if the constructor had set it. A promise NullAway cannot check, so keep
 * it to that lifecycle (CLAUDE.md, "Null annotations"); Editora's.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Initializer {}
