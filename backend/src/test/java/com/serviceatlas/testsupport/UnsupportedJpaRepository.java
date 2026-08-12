package com.serviceatlas.testsupport;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.FluentQuery;

/**
 * Base for hand-written in-memory repositories in tests.
 *
 * <p>{@link JpaRepository} has a wide surface, and a stub only ever needs three or four methods.
 * Everything else throws, so a test that quietly starts depending on an unimplemented method fails
 * loudly instead of silently doing nothing.
 */
public abstract class UnsupportedJpaRepository<T, ID> implements JpaRepository<T, ID> {

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Not implemented by this test stub");
    }

    @Override
    public void flush() {
        throw unsupported();
    }

    @Override
    public <S extends T> S saveAndFlush(S entity) {
        throw unsupported();
    }

    @Override
    public <S extends T> List<S> saveAllAndFlush(Iterable<S> entities) {
        throw unsupported();
    }

    @Override
    public void deleteAllInBatch(Iterable<T> entities) {
        throw unsupported();
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<ID> ids) {
        throw unsupported();
    }

    @Override
    public void deleteAllInBatch() {
        throw unsupported();
    }

    @Override
    public T getOne(ID id) {
        throw unsupported();
    }

    @Override
    public T getById(ID id) {
        throw unsupported();
    }

    @Override
    public T getReferenceById(ID id) {
        throw unsupported();
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example) {
        throw unsupported();
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example, Sort sort) {
        throw unsupported();
    }

    @Override
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        throw unsupported();
    }

    @Override
    public List<T> findAll() {
        throw unsupported();
    }

    @Override
    public List<T> findAllById(Iterable<ID> ids) {
        throw unsupported();
    }

    @Override
    public <S extends T> S save(S entity) {
        throw unsupported();
    }

    @Override
    public Optional<T> findById(ID id) {
        throw unsupported();
    }

    @Override
    public boolean existsById(ID id) {
        throw unsupported();
    }

    @Override
    public long count() {
        throw unsupported();
    }

    @Override
    public void deleteById(ID id) {
        throw unsupported();
    }

    @Override
    public void delete(T entity) {
        throw unsupported();
    }

    @Override
    public void deleteAllById(Iterable<? extends ID> ids) {
        throw unsupported();
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        throw unsupported();
    }

    @Override
    public void deleteAll() {
        throw unsupported();
    }

    @Override
    public List<T> findAll(Sort sort) {
        throw unsupported();
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        throw unsupported();
    }

    @Override
    public <S extends T> Optional<S> findOne(Example<S> example) {
        throw unsupported();
    }

    @Override
    public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw unsupported();
    }

    @Override
    public <S extends T> long count(Example<S> example) {
        throw unsupported();
    }

    @Override
    public <S extends T> boolean exists(Example<S> example) {
        throw unsupported();
    }

    @Override
    public <S extends T, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        throw unsupported();
    }
}
