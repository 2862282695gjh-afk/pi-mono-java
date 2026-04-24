import { useState } from "react";
import type { DietRecommendation, MealItem } from "./types";
import { MEAL_LABELS } from "./types";

interface DietRecommendCardProps {
  recommendation: DietRecommendation;
}

interface NutrientBarProps {
  label: string;
  current: number;
  goal: number;
  unit: string;
  color: string;
}

function NutrientBar({ label, current, goal, unit, color }: NutrientBarProps) {
  const pct = goal > 0 ? Math.min((current / goal) * 100, 100) : 0;
  return (
    <div className="duo-nutrient-row">
      <span className="duo-nutrient-label">{label}</span>
      <div className="duo-nutrient-track">
        <div className="duo-nutrient-fill" style={{ width: `${pct}%`, backgroundColor: color }} />
      </div>
      <span className="duo-nutrient-value">
        {current}{unit} / {goal}{unit}
      </span>
    </div>
  );
}

function WaterTracker({ current, goal }: { current: number; goal: number }) {
  const [cups, setCups] = useState(current);
  const remaining = Math.max(goal - cups, 0);
  const pct = goal > 0 ? Math.min((cups / goal) * 100, 100) : 0;

  const handleDrink = () => {
    if (cups < goal) setCups((n) => n + 1);
  };

  return (
    <div className="duo-water-section">
      <div className="duo-water-header">
        <span className="duo-water-icon">💧</span>
        <div className="duo-water-info">
          <span className="duo-water-title">饮水追踪</span>
          <span className="duo-water-detail">{cups} / {goal} 杯 · {remaining > 0 ? `还需 ${remaining} 杯` : "目标达成！"}</span>
        </div>
        <button
          className={`duo-water-btn ${cups >= goal ? "done" : ""}`}
          onClick={handleDrink}
          disabled={cups >= goal}
        >
          {cups >= goal ? "✓" : "+1"}
        </button>
      </div>
      <div className="duo-water-track">
        <div className="duo-water-fill" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

const MEAL_ICONS: Record<MealItem["mealType"], string> = {
  breakfast: "🌅",
  lunch: "☀️",
  dinner: "🌙",
  snack: "🍪",
};

const MEAL_ACCENT_COLORS: Record<MealItem["mealType"], string> = {
  breakfast: "#FFC800",
  lunch: "#FF9600",
  dinner: "#1CB0F6",
  snack: "#CE82FF",
};

function MealCard({ meal }: { meal: MealItem }) {
  const [expanded, setExpanded] = useState(false);
  const accentColor = MEAL_ACCENT_COLORS[meal.mealType];

  return (
    <div
      className="duo-meal-card"
      style={{ borderLeftColor: accentColor }}
    >
      <button className="duo-meal-header" onClick={() => setExpanded(!expanded)}>
        <span className="duo-meal-type-icon">{MEAL_ICONS[meal.mealType]}</span>
        <div className="duo-meal-info">
          <span className="duo-meal-type">{MEAL_LABELS[meal.mealType]}</span>
          <span className="duo-meal-name">{meal.name}</span>
        </div>
        <div className="duo-meal-right">
          <span className="duo-meal-cal">{meal.calories} kcal</span>
          <span className={`duo-meal-expand ${expanded ? "open" : ""}`}>▾</span>
        </div>
      </button>
      {expanded && (
        <div className="duo-meal-detail">
          <div className="duo-macro-grid">
            <div className="duo-macro-item">
              <span className="duo-macro-value" style={{ color: "#CE82FF" }}>{meal.protein}g</span>
              <span className="duo-macro-label">蛋白质</span>
            </div>
            <div className="duo-macro-item">
              <span className="duo-macro-value" style={{ color: "#FFC800" }}>{meal.carbs}g</span>
              <span className="duo-macro-label">碳水</span>
            </div>
            <div className="duo-macro-item">
              <span className="duo-macro-value" style={{ color: "#FF4B4B" }}>{meal.fat}g</span>
              <span className="duo-macro-label">脂肪</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export function DietRecommendCard({ recommendation }: DietRecommendCardProps) {
  const { nutrition, meals, waterIntake, waterGoal } = recommendation;

  return (
    <div className="duo-card duo-diet-card">
      {/* 卡片头部 */}
      <div className="duo-card-header">
        <div className="duo-header-left">
          <h3 className="duo-card-title">饮食推荐</h3>
          <span className="duo-calorie-badge">
            <span className="duo-calorie-icon">🍎</span>
            {nutrition.calories} / {nutrition.caloriesGoal} kcal
          </span>
        </div>
      </div>

      {/* 营养素进度 */}
      <div className="duo-nutrient-section">
        <NutrientBar
          label="蛋白质"
          current={nutrition.protein}
          goal={nutrition.proteinGoal}
          unit="g"
          color="#CE82FF"
        />
        <NutrientBar
          label="碳水"
          current={nutrition.carbs}
          goal={nutrition.carbsGoal}
          unit="g"
          color="#FFC800"
        />
        <NutrientBar
          label="脂肪"
          current={nutrition.fat}
          goal={nutrition.fatGoal}
          unit="g"
          color="#FF4B4B"
        />
      </div>

      {/* 三餐推荐 */}
      <div className="duo-meal-list">
        {meals.map((meal) => (
          <MealCard key={meal.id} meal={meal} />
        ))}
      </div>

      {/* 饮水追踪 */}
      <div className="duo-card-footer">
        <WaterTracker current={waterIntake} goal={waterGoal} />
      </div>
    </div>
  );
}
