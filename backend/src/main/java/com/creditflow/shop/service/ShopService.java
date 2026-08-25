package com.creditflow.shop.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.config.AppProperties;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.dto.ShopRequest;
import com.creditflow.shop.dto.ShopResponse;
import com.creditflow.shop.mapper.ShopMapper;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopRepository shopRepository;
    private final ShopMapper shopMapper;
    private final AppProperties properties;

    @Transactional(readOnly = true)
    public List<ShopResponse> list() {
        return shopRepository.findAllByOrderByNameAsc()
                .stream()
                .map(shopMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ShopResponse findById(Long id) {
        return shopMapper.toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Shop getEntity(Long id) {
        return shopRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Boutique", id));
    }

    @Transactional
    public ShopResponse create(ShopRequest request) {
        assertPlanAllowsActive(effectiveActive(request, null), false, null);
        assertNameAvailable(request.name(), null);

        Shop shop = shopMapper.toEntity(request);
        Shop saved = shopRepository.save(shop);
        log.info("Boutique creee: {} ({})", saved.getName(), saved.getId());
        return shopMapper.toResponse(saved);
    }

    @Transactional
    public ShopResponse update(Long id, ShopRequest request) {
        Shop shop = getEntity(id);
        assertPlanAllowsActive(effectiveActive(request, shop), shop.isActive(), id);
        assertNameAvailable(request.name(), id);

        shopMapper.updateEntity(request, shop);
        return shopMapper.toResponse(shopRepository.save(shop));
    }

    @Transactional
    public void delete(Long id) {
        shopRepository.delete(getEntity(id));
        log.info("Boutique supprimee: {}", id);
    }

    private void assertNameAvailable(String name, Long id) {
        boolean exists = id == null
                ? shopRepository.existsByNameIgnoreCase(name)
                : shopRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (exists) {
            throw new BusinessRuleException("Une boutique utilise deja le nom " + name);
        }
    }

    /** Etat actif resultant de la requete, en repliquant la semantique de ShopMapper
     *  (create: null -> true ; update: null -> etat courant inchange). */
    private boolean effectiveActive(ShopRequest request, Shop existing) {
        if (request.active() != null) {
            return request.active();
        }
        return existing == null || existing.isActive();
    }

    /**
     * N'evalue le plan que sur une veritable activation (creation active, ou reactivation
     * d'une boutique jusque-la inactive) : une boutique deja active qui reste active ne doit
     * jamais etre bloquee retroactivement, meme si d'autres boutiques actives existent deja
     * en base sur une instance dont le plan a ete degrade apres coup.
     */
    private void assertPlanAllowsActive(boolean requestedActive, boolean wasActive, Long excludingShopId) {
        if (!requestedActive || wasActive || properties.getPlan().isMultiShop()) {
            return;
        }
        boolean anotherActiveShopExists = excludingShopId == null
                ? shopRepository.countByActiveTrue() > 0
                : shopRepository.existsByActiveTrueAndIdNot(excludingShopId);
        if (anotherActiveShopExists) {
            throw new BusinessRuleException(
                    "Votre formule actuelle ne permet qu'une seule boutique active. "
                    + "Contactez l'exploitant de la plateforme pour passer à la formule Multi-boutiques.");
        }
    }
}
