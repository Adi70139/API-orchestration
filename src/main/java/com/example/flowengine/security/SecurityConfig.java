package com.example.flowengine.security;

import com.example.flowengine.entity.User;
import com.example.flowengine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.LocalDateTime;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        // Return 401 JSON for unauthenticated API requests instead of
                        // redirecting to OAuth2 login — which causes the frontend to follow
                        // the redirect and lose the original request.
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(
                                    "{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        http.oauth2Login(oauth -> oauth
                .successHandler((request, response, authentication) -> {
                    OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
                    String email = oAuth2User.getAttribute("email");
                    String name  = oAuth2User.getAttribute("name");
                    String sub   = oAuth2User.getAttribute("sub");

                    User user = userRepository
                            .findByProviderAndProviderId(User.Provider.GOOGLE, sub)
                            .orElseGet(() -> {
                                if (userRepository.existsByEmail(email)) {
                                    return userRepository.findByEmail(email).map(u -> {
                                        u.setProvider(User.Provider.GOOGLE);
                                        u.setProviderId(sub);
                                        return userRepository.save(u);
                                    }).orElseThrow();
                                }
                                User newUser = new User();
                                newUser.setEmail(email);
                                newUser.setName(name);
                                newUser.setProvider(User.Provider.GOOGLE);
                                newUser.setProviderId(sub);
                                newUser.setRole(User.Role.USER);
                                newUser.setCreatedAt(LocalDateTime.now());
                                return userRepository.save(newUser);
                            });

                    String token = jwtUtil.generate(user);
                    // Read frontend redirect URL from session (set before OAuth initiation)
                    // Fall back to configured frontend URL

                    response.sendRedirect(frontendUrl + "/auth/callback?token=" + token);
                })
                .failureHandler((request, response, ex) -> {

                    String reason = ex.getMessage() != null
                            ? java.net.URLEncoder.encode(ex.getMessage(), java.nio.charset.StandardCharsets.UTF_8)
                            : "unknown";
                    response.sendRedirect(frontendUrl + "/auth/callback?error=oauth_failed&reason=" + reason);
                })
        );

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(userDetailsService());
        p.setPasswordEncoder(passwordEncoder());
        return p;
    }

    @Bean
    public AuthenticationManager authManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}