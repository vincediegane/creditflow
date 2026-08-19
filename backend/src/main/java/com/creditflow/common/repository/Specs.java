package com.creditflow.common.repository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import org.springframework.data.jpa.domain.Specification;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Aides a la construction de criteres de recherche.
 *
 * <p>Les filtres sont construits uniquement lorsqu'ils sont renseignes : la
 * requete SQL ne contient donc jamais de predicat inutile, et aucun parametre
 * n'est envoye a la base sans type determinable.</p>
 */
public final class Specs {

    private Specs() {
    }

    /** Critere neutre (aucun filtre). */
    public static <T> Specification<T> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Combine les criteres non nuls avec un ET logique. */
    @SafeVarargs
    public static <T> Specification<T> combine(Specification<T>... specifications) {
        List<Specification<T>> present = Arrays.stream(specifications)
                .filter(Objects::nonNull)
                .toList();
        return present.stream().reduce(Specification::and).orElseGet(Specs::all);
    }

    /** Motif «contient», insensible a la casse. */
    public static String contains(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /** {@code LOWER(path) LIKE %value%} */
    public static jakarta.persistence.criteria.Predicate likeIgnoreCase(
            jakarta.persistence.criteria.CriteriaBuilder cb, Path<String> path, String value) {
        return cb.like(cb.lower(path), contains(value));
    }

    /** {@code LOWER(expression) LIKE %value%} */
    public static jakarta.persistence.criteria.Predicate likeIgnoreCase(
            jakarta.persistence.criteria.CriteriaBuilder cb, Expression<String> expression, String value) {
        return cb.like(cb.lower(expression), contains(value));
    }
}
