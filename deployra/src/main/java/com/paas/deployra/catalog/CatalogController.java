package com.paas.deployra.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API công khai cho trang bảng giá. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/plans")
    public List<CatalogService.PlanView> plans() {
        return catalog.listPlans();
    }

    @GetMapping("/addons")
    public List<CatalogService.AddonView> addons() {
        return catalog.listAddons();
    }
}
