package com.creditflow.auth.service;

import com.creditflow.auth.domain.User;
import com.creditflow.auth.dto.UserRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAllByOrderByFullNameAsc()
                .stream()
                .map(UserService::toResponse)
                .toList();
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        if (userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw new BusinessRuleException("Ce nom d'utilisateur est deja utilise");
        }

        User user = User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(request.role())
                .enabled(true)
                .mustChangePassword(true)
                .build();

        User saved = userRepository.save(user);
        log.info("Compte utilisateur cree: {} ({})", saved.getUsername(), saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public UserResponse setEnabled(Long id, boolean enabled, String currentUsername) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Utilisateur", id));

        if (!enabled && user.getUsername().equalsIgnoreCase(currentUsername)) {
            throw new BusinessRuleException("Vous ne pouvez pas desactiver votre propre compte");
        }

        user.setEnabled(enabled);
        User saved = userRepository.save(user);
        log.info("Compte utilisateur {} {}", saved.getUsername(), enabled ? "active" : "desactive");
        return toResponse(saved);
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled());
    }
}
