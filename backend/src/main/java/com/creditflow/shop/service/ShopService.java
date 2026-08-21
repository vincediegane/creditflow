package com.creditflow.shop.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
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
        assertNameAvailable(request.name(), null);

        Shop shop = shopMapper.toEntity(request);
        Shop saved = shopRepository.save(shop);
        log.info("Boutique creee: {} ({})", saved.getName(), saved.getId());
        return shopMapper.toResponse(saved);
    }

    @Transactional
    public ShopResponse update(Long id, ShopRequest request) {
        Shop shop = getEntity(id);
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
}
