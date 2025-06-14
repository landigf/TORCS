import pandas as pd
import numpy as np
from sklearn.decomposition import PCA
from sklearn.preprocessing import StandardScaler
from sklearn.neighbors import KNeighborsRegressor
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error

import matplotlib.pyplot as plt

def analyze_features_for_behavioral_cloning():
    # Carica il dataset
    df = pd.read_csv('classes/dataset_union.csv')
    
    # Definisci le features di input e output per behavioral cloning
    input_features = ['time', 'angle', 'curLapTime', 'distanceFromStart', 'fuel', 'damage', 
                     'gear', 'rpm', 'speedX', 'speedY', 'speedZ', 'lastLapTime',
                     'track0', 'track1', 'track2', 'track3', 'track4', 'track5', 
                     'track6', 'track7', 'track8', 'track9', 'track10', 'track11', 
                     'track12', 'track13', 'track14', 'track15', 'track16', 'track17', 
                     'track18', 'wheel0', 'wheel1', 'wheel2', 'wheel3', 'trackPos']
    
    output_features = ['steer', 'accel', 'brake']
    
    # Rimuovi righe con valori mancanti
    df_clean = df.dropna()
    
    X = df_clean[input_features]
    y = df_clean[output_features]
    
    # Standardizza le features
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)
    
    # Applica PCA
    pca = PCA()
    X_pca = pca.fit_transform(X_scaled)
    
    # Analizza la varianza spiegata
    cumulative_variance = np.cumsum(pca.explained_variance_ratio_)
    
    # Trova il numero di componenti per spiegare il 95% della varianza
    n_components_95 = np.argmax(cumulative_variance >= 0.95) + 1
    print(f"Componenti necessarie per 95% varianza: {n_components_95}")
    
    # Visualizza la varianza spiegata
    plt.figure(figsize=(12, 5))
    
    plt.subplot(1, 2, 1)
    plt.plot(range(1, len(cumulative_variance) + 1), cumulative_variance, 'bo-')
    plt.axhline(y=0.95, color='r', linestyle='--', label='95% varianza')
    plt.xlabel('Numero di Componenti')
    plt.ylabel('Varianza Cumulativa Spiegata')
    plt.title('Varianza Spiegata dalle Componenti PCA')
    plt.legend()
    plt.grid(True)
    
    plt.subplot(1, 2, 2)
    plt.bar(range(1, 21), pca.explained_variance_ratio_[:20])
    plt.xlabel('Componente PCA')
    plt.ylabel('Varianza Spiegata')
    plt.title('Varianza Spiegata per Componente (Prime 20)')
    plt.grid(True)
    
    plt.tight_layout()
    plt.savefig('pca_analysis.png')
    plt.show()
    
    # Analizza l'importanza delle features originali
    feature_importance = np.abs(pca.components_[:n_components_95]).mean(axis=0)
    feature_ranking = pd.DataFrame({
        'feature': input_features,
        'importance': feature_importance
    }).sort_values('importance', ascending=False)
    
    print("\nRanking delle features più importanti:")
    print(feature_ranking.head(15))
    
    # Test delle performance con diverse combinazioni di features
    top_features = feature_ranking.head(10)['feature'].tolist()
    
    results = {}
    
    # Test con tutte le features
    X_train, X_test, y_train, y_test = train_test_split(X_scaled, y, test_size=0.2, random_state=42)
    knn_all = KNeighborsRegressor(n_neighbors=5)
    knn_all.fit(X_train, y_train)
    y_pred_all = knn_all.predict(X_test)
    mse_all = mean_squared_error(y_test, y_pred_all)
    results['tutte_features'] = mse_all
    
    # Test con top features
    X_top = X[top_features]
    X_top_scaled = scaler.fit_transform(X_top)
    X_train_top, X_test_top, y_train, y_test = train_test_split(X_top_scaled, y, test_size=0.2, random_state=42)
    knn_top = KNeighborsRegressor(n_neighbors=5)
    knn_top.fit(X_train_top, y_train)
    y_pred_top = knn_top.predict(X_test_top)
    mse_top = mean_squared_error(y_test, y_pred_top)
    results['top_10_features'] = mse_top
    
    # Test con componenti PCA
    X_pca_reduced = X_pca[:, :n_components_95]
    X_train_pca, X_test_pca, y_train, y_test = train_test_split(X_pca_reduced, y, test_size=0.2, random_state=42)
    knn_pca = KNeighborsRegressor(n_neighbors=5)
    knn_pca.fit(X_train_pca, y_train)
    y_pred_pca = knn_pca.predict(X_test_pca)
    mse_pca = mean_squared_error(y_test, y_pred_pca)
    results['pca_components'] = mse_pca
    
    print("\nPerformance KNN (MSE):")
    for method, mse in results.items():
        print(f"{method}: {mse:.6f}")
    
    print(f"\nRaccomandazioni per behavioral cloning:")
    print(f"1. Le {len(top_features)} features più importanti sono: {top_features}")
    print(f"2. Puoi ridurre a {n_components_95} componenti PCA mantenendo 95% della varianza")
    print(f"3. Le features track (sensori) sembrano essere molto importanti per la guida")
    
    return feature_ranking, results

if __name__ == "__main__":
    feature_ranking, results = analyze_features_for_behavioral_cloning()