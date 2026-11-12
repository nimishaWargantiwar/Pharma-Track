package com.pharmatrack.security;

import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.UserAccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads active or disabled user details from {@link UserAccountRepository} using normalized lowercase usernames.
 */
@Service
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;

    public CustomUserDetailsService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = UserAccount.normalizeUsername(username);
        if (normalized == null || normalized.isEmpty()) {
            throw new UsernameNotFoundException("Invalid username or password");
        }
        UserAccount user = userAccountRepository.findByUsernameIgnoreCase(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
        return CustomUserDetails.fromEntity(user);
    }
}

