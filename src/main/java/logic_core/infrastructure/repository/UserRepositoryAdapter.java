package logic_core.infrastructure.repository;

import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import logic_core.infrastructure.mapper.UserEntityMapper;
import logic_core.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Transactional
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository userJpaRepository;

    public UserRepositoryAdapter(UserJpaRepository userJpaRepository) {
        this.userJpaRepository = userJpaRepository;
    }

    @Override
    public Optional<UserModel> save(UserModel user) {

        UserEntity entity;

        if(user.getId()==null){
            entity = UserEntityMapper.toPersistence(user);
        }
        else{
            entity = userJpaRepository
                    .findByIdAndIsDeletedFalse(user.getId())
                    .orElseThrow();

            UserEntityMapper.updateEntity(entity,user);
        }


        return Optional.of(
                UserEntityMapper.toModel(
                        userJpaRepository.save(entity)
                )
        );
    }

    @Override
    public void update(UserModel user) {
        userJpaRepository.findByIdAndIsDeletedFalse(user.getId()).ifPresent(entity -> {
            UserEntityMapper.updateEntity(entity, user);
            userJpaRepository.save(entity);
        });
    }

    @Override
    public void delete(UserModel user) {
        userJpaRepository.findByIdAndIsDeletedFalse(user.getId()).ifPresent(entity -> {
            entity.markDeleted();
            userJpaRepository.save(entity);
        });
    }

    @Override
    public Optional<UserModel> findById(UUID userId) {
        return userJpaRepository.findByIdAndIsDeletedFalse(userId)
                .map(UserEntityMapper::toModel);
    }

    @Override
    public Optional<UserModel> findByUsername(String username) {
        return userJpaRepository.findByUsernameAndIsDeletedFalse(username)
                .map(UserEntityMapper::toModel);
    }

    @Override
    public Optional<UserModel> findByEmail(String email) {
        return userJpaRepository.findByEmailAndIsDeletedFalse(email)
                .map(UserEntityMapper::toModel);
    }

    @Override
    public boolean existsByUsername(String username) {
        return userJpaRepository.existsByUsernameAndIsDeletedFalse(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userJpaRepository.existsByEmailAndIsDeletedFalse(email);
    }

    @Override
    public boolean existsById(UUID userId) {
        return userJpaRepository.existsByIdAndIsDeletedFalse(userId);
    }

    @Override
    public Optional<UserModel> findByIdForUpdate(UUID userId) {
        return userJpaRepository.findWithLockByIdAndIsDeletedFalse(userId)
                .map(UserEntityMapper::toModel);
    }

    @Override
    public Optional<UserModel> findByUsernameForUpdate(String username) {
        return userJpaRepository.findWithLockByUsernameAndIsDeletedFalse(username)
                .map(UserEntityMapper::toModel);
    }

    @Override
    public Optional<UserModel> findUserBySessionId(UUID sessionId) {
        return userJpaRepository.findUserBySessionId(sessionId)
                .map(UserEntityMapper::toModel);
    }

    @Override
    public boolean isActive(UserModel user) {
        return userJpaRepository.existsByIdAndIsActiveTrueAndIsDeletedFalse(user.getId());
    }

    @Override
    public List<UserModel> searchUsers(UUID actorId, String query, int limit, int pageSize) {
        return userJpaRepository.searchUsers(actorId, query, PageRequest.of(0, limit))
                .stream()
                .map(UserEntityMapper::toModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserModel> findByIds(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userJpaRepository.findAllById(userIds).stream()
                .map(UserEntityMapper::toModel)
                .collect(Collectors.toList());
    }
}
