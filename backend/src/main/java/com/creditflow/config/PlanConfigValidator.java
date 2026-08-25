package com.creditflow.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Garde-fou de formule.
 *
 * <p>Le canal WhatsApp automatique est fige par bean Spring au demarrage
 * ({@code app.notification.channel=whatsapp}) : il n'existe aucun endpoint qui le bascule a
 * chaud. Si la formule de cette instance ({@code app.plan.whatsapp-auto}) ne l'inclut pas,
 * l'application refuse donc de demarrer. Actif dans tous les profils : le gating de formule
 * est une contrainte commerciale, independante du mode demo ou strict.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanConfigValidator {

    private final AppProperties properties;

    @PostConstruct
    void validate() {
        boolean whatsappChannelSelected = "whatsapp".equals(properties.getNotification().getChannel());
        if (whatsappChannelSelected && !properties.getPlan().isWhatsappAuto()) {
            throw new IllegalStateException("""
                    Demarrage refuse : NOTIFICATION_CHANNEL=whatsapp est configure mais la \
                    formule de cette instance (PLAN_WHATSAPP_AUTO=false) n'inclut pas le canal \
                    WhatsApp automatique.

                    Corrigez l'une des deux valeurs : NOTIFICATION_CHANNEL=manual pour rester \
                    sur cette formule, ou PLAN_WHATSAPP_AUTO=true si la formule vendue inclut \
                    bien WhatsApp automatique, puis redemarrez.""");
        }
        log.info("Controle de plan au demarrage : configuration WhatsApp coherente avec la formule.");
    }
}
