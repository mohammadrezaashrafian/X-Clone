package logic_core.infrastructure.mapper;

import logic_core.domain.model.UserModel;
import logic_core.infrastructure.persistence.entity.UserEntity;

public final class UserEntityMapper
{
    private UserEntityMapper()
    {
    }

    public static UserModel toModel(UserEntity entity)
    {
        if (entity == null)
        {
            return null;
        }

        return UserModel.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .email(entity.getEmail())
                .passwordHash(entity.getPasswordHash())
                .displayName(entity.getDisplayName())
                .bio(entity.getBio())
                .avatarUrl(entity.getAvatarUrl())
                .bannerUrl(entity.getBannerUrl())
                .verified(entity.isVerified())
                .active(entity.isActive())
                .deleted(entity.isDeleted())
                .emailVerified(entity.isEmailVerified())
                .pendingEmail(entity.getPendingEmail())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static UserEntity toPersistence(UserModel model)
    {
        if (model == null)
        {
            return null;
        }

        UserEntity entity = new UserEntity();
        updateEntity(entity, model);
        return entity;
    }

    public static void updateEntity(UserEntity entity, UserModel model)
    {
        if (entity == null || model == null)
        {
            return;
        }

        entity.setUsername(model.getUsername());
        entity.setEmail(model.getEmail());
        entity.setPasswordHash(model.getPasswordHash());
        entity.setDisplayName(model.getDisplayName());
        entity.setBio(model.getBio());
        entity.setAvatarUrl(model.getAvatarUrl());
        entity.setBannerUrl(model.getBannerUrl());
        entity.setVerified(model.isVerified());
        entity.setActive(model.isActive());
        entity.setEmailVerified(model.isEmailVerified());
        entity.setPendingEmail(model.getPendingEmail());
        if (model.isDeleted()) {
            entity.markDeleted();
        }
    }
}